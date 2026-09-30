package dev.stealth.core.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WireMockTest
class CachedHttpClientTest {

    private static final String PATH = "/metadata.xml";

    @TempDir private Path cacheDirectory;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-09-29T12:00:00Z"));
    private final AtomicBoolean offline = new AtomicBoolean();
    private URI uri;
    private CachedHttpClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        uri = URI.create(wireMock.getHttpBaseUrl() + PATH);
        HttpCache cache = new HttpCache(cacheDirectory, Duration.ofHours(24), clock);
        client =
                new CachedHttpClient(
                        HttpClient.newHttpClient(), cache, offline::get, 3, Duration.ZERO);
    }

    @Test
    void get_freshCacheEntry_doesNotRequestAgain() throws Exception {
        stubFor(get(PATH).willReturn(ok("v1")));

        client.get(uri);
        clock.advance(Duration.ofHours(23));

        assertThat(client.get(uri)).contains("v1");
        verify(exactly(1), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void get_expiredCacheEntry_requestsAgain() throws Exception {
        stubFor(get(PATH).willReturn(ok("v1")));
        client.get(uri);
        stubFor(get(PATH).willReturn(ok("v2")));
        clock.advance(Duration.ofHours(25));

        assertThat(client.get(uri)).contains("v2");
        verify(exactly(2), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void get_offlineWithExpiredEntry_servesTheStaleEntry() throws Exception {
        stubFor(get(PATH).willReturn(ok("v1")));
        client.get(uri);
        clock.advance(Duration.ofDays(30));
        offline.set(true);

        assertThat(client.get(uri)).contains("v1");
        verify(exactly(1), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void get_offlineWithNothingCached_throwsWithoutRequesting() {
        offline.set(true);

        assertThatThrownBy(() -> client.get(uri))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("offline");
        verify(exactly(0), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void get_notFound_returnsEmptyAndCachesIt() throws Exception {
        stubFor(get(PATH).willReturn(aResponse().withStatus(404)));

        assertThat(client.get(uri)).isEmpty();
        assertThat(client.get(uri)).isEmpty();
        verify(exactly(1), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void get_rateLimitedThenOk_retriesAndReturnsBody() throws Exception {
        stubFor(
                get(PATH)
                        .inScenario("rate limit")
                        .whenScenarioStateIs(STARTED)
                        .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                        .willSetStateTo("recovered"));
        stubFor(
                get(PATH)
                        .inScenario("rate limit")
                        .whenScenarioStateIs("recovered")
                        .willReturn(ok("v1")));

        assertThat(client.get(uri)).contains("v1");
        verify(exactly(2), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void get_serverErrorEveryAttempt_throwsAfterMaxAttempts() {
        stubFor(get(PATH).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.get(uri))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 503");
        verify(exactly(3), getRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void get_serverErrorWithStaleEntry_servesTheStaleEntry() throws Exception {
        stubFor(get(PATH).willReturn(ok("v1")));
        client.get(uri);
        clock.advance(Duration.ofHours(25));
        stubFor(get(PATH).willReturn(aResponse().withStatus(503)));

        assertThat(client.get(uri)).contains("v1");
    }

    @Test
    void get_clientError_failsWithoutRetrying() {
        stubFor(get(PATH).willReturn(aResponse().withStatus(403)));

        assertThatThrownBy(() -> client.get(uri)).hasMessageContaining("HTTP 403");
        verify(exactly(1), getRequestedFor(urlEqualTo(PATH)));
    }

    /** A clock the test moves forward by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
