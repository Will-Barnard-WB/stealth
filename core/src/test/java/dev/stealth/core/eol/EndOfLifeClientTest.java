package dev.stealth.core.eol;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import dev.stealth.core.http.CachedHttpClient;
import dev.stealth.core.http.HttpCache;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WireMockTest
class EndOfLifeClientTest {

    @TempDir private Path cacheDirectory;

    private EndOfLifeClient client;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        CachedHttpClient http =
                new CachedHttpClient(
                        HttpClient.newHttpClient(),
                        new HttpCache(cacheDirectory, Duration.ofHours(24), Clock.systemUTC()),
                        () -> false,
                        1,
                        Duration.ZERO);
        client =
                new EndOfLifeClient(
                        http, URI.create(wireMock.getHttpBaseUrl() + RecordedEndOfLife.BASE_PATH));
    }

    @Test
    void cycles_recordedSpringBoot_readsSupportDatesAndLatestRelease() throws Exception {
        RecordedEndOfLife.stubAll();

        List<ReleaseCycle> cycles = client.cycles("spring-boot").orElseThrow();

        assertThat(cycles).first().extracting(ReleaseCycle::cycle).isEqualTo("4.1");
        assertThat(cycle(cycles, "2.7"))
                .isEqualTo(
                        new ReleaseCycle(
                                "2.7",
                                new Support.Until(LocalDate.of(2023, 6, 30)),
                                new Support.Until(LocalDate.of(2029, 6, 30)),
                                Optional.of("2.7.18")));
    }

    @Test
    void cycles_recordedTemurin_hasNoCommercialSupport() throws Exception {
        RecordedEndOfLife.stubAll();

        List<ReleaseCycle> cycles = client.cycles("eclipse-temurin").orElseThrow();

        assertThat(cycle(cycles, "21"))
                .isEqualTo(
                        new ReleaseCycle(
                                "21",
                                new Support.Until(LocalDate.of(2029, 12, 31)),
                                new Support.Open(),
                                Optional.of("21.0.12.1+1")));
    }

    @Test
    void cycles_untrackedProduct_returnsEmpty() throws Exception {
        stubFor(get(urlEqualTo("/api/cobol.json")).willReturn(aResponse().withStatus(404)));

        assertThat(client.cycles("cobol")).isEmpty();
    }

    @Test
    void cycles_productSlugWithAPathSegment_isRejectedWithoutRequesting() {
        assertThatThrownBy(() -> client.cycles("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an endoflife.date product");
    }

    @Test
    void cycles_malformedBody_throws() {
        stubFor(get(urlEqualTo("/api/spring-boot.json")).willReturn(aResponse().withBody("{")));

        assertThatThrownBy(() -> client.cycles("spring-boot"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Malformed response");
    }

    @Test
    void cycles_bodyThatIsNotAnArray_throws() {
        stubFor(
                get(urlEqualTo("/api/spring-boot.json"))
                        .willReturn(aResponse().withBody("{\"cycle\": \"4.1\"}")));

        assertThatThrownBy(() -> client.cycles("spring-boot"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Expected an array");
    }

    @Test
    void parse_booleanAndMissingSupportFields_distinguishesEndedFromOpen() throws Exception {
        String json =
                """
                [
                  {"cycle": "3.0", "eol": true, "extendedSupport": false},
                  {"cycle": "2.0", "eol": false},
                  {"cycle": "1.0", "eol": "not a date", "latest": "  "},
                  {"releaseDate": "2020-01-01"}
                ]
                """;

        List<ReleaseCycle> cycles = EndOfLifeClient.parse(json, URI.create("urn:test"));

        assertThat(cycles)
                .extracting(ReleaseCycle::cycle, ReleaseCycle::support, ReleaseCycle::latest)
                .containsExactly(
                        Tuple.tuple("3.0", new Support.Ended(), Optional.empty()),
                        Tuple.tuple("2.0", new Support.Open(), Optional.empty()),
                        Tuple.tuple("1.0", new Support.Open(), Optional.empty()));
    }

    private static ReleaseCycle cycle(List<ReleaseCycle> cycles, String cycle) {
        return cycles.stream().filter(c -> c.cycle().equals(cycle)).findFirst().orElseThrow();
    }
}
