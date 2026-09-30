package dev.stealth.core.vuln;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@WireMockTest
class OsvClientTest {

    private static final OsvClient.Package TEXT =
            new OsvClient.Package("org.apache.commons:commons-text", "1.9");
    private static final OsvClient.Package LANG =
            new OsvClient.Package("org.apache.commons:commons-lang3", "3.9");
    private static final OsvClient.Package GUAVA =
            new OsvClient.Package("com.google.guava:guava", "30.1-jre");

    @TempDir private Path cacheDirectory;

    private URI api;
    private CachedHttpClient http;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wireMock) {
        api = URI.create(wireMock.getHttpBaseUrl() + "/osv/");
        http =
                new CachedHttpClient(
                        HttpClient.newHttpClient(),
                        new HttpCache(cacheDirectory, Duration.ofHours(24), Clock.systemUTC()),
                        () -> false,
                        1,
                        Duration.ZERO);
    }

    @Test
    void query_packages_sendsOneSortedBatchAndMapsResultsBack() throws Exception {
        stubFor(
                post("/osv/v1/querybatch")
                        .withRequestBody(
                                equalToJson(OsvClient.batchRequest(List.of(GUAVA, LANG, TEXT))))
                        .willReturn(
                                okJson(
                                        """
                                        {"results":[
                                          {"vulns":[{"id":"GHSA-7g45-4rm6-3mm3"},{"id":"GHSA-5mg8-w23w-74h3"}]},
                                          {},
                                          {"vulns":[{"id":"GHSA-599f-7c49-w659"}]}
                                        ]}\
                                        """)));

        Map<OsvClient.Package, List<String>> ids =
                new OsvClient(http, api).query(List.of(TEXT, LANG, GUAVA, TEXT));

        assertThat(ids)
                .containsEntry(GUAVA, List.of("GHSA-7g45-4rm6-3mm3", "GHSA-5mg8-w23w-74h3"))
                .containsEntry(LANG, List.of())
                .containsEntry(TEXT, List.of("GHSA-599f-7c49-w659"))
                .hasSize(3);
    }

    @Test
    void query_moreThanOneBatch_splitsIntoChunks() throws Exception {
        stubFor(
                post("/osv/v1/querybatch")
                        .withRequestBody(equalToJson(OsvClient.batchRequest(List.of(GUAVA, LANG))))
                        .willReturn(okJson("{\"results\":[{},{}]}")));
        stubFor(
                post("/osv/v1/querybatch")
                        .withRequestBody(equalToJson(OsvClient.batchRequest(List.of(TEXT))))
                        .willReturn(okJson("{\"results\":[{\"vulns\":[{\"id\":\"GHSA-1\"}]}]}")));

        Map<OsvClient.Package, List<String>> ids =
                new OsvClient(http, api, 2).query(List.of(TEXT, LANG, GUAVA));

        assertThat(ids).containsEntry(TEXT, List.of("GHSA-1"));
        verify(exactly(2), postRequestedFor(urlEqualTo("/osv/v1/querybatch")));
    }

    @Test
    void query_pagedResult_fetchesTheRemainingPages() throws Exception {
        stubFor(
                post("/osv/v1/querybatch")
                        .willReturn(
                                okJson(
                                        "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-1\"}],"
                                                + "\"next_page_token\":\"page2\"}]}")));
        stubFor(
                post("/osv/v1/query")
                        .withRequestBody(
                                equalToJson(
                                        "{\"package\":{\"ecosystem\":\"Maven\",\"name\":\"org.apache.commons:commons-text\"},"
                                            + "\"version\":\"1.9\",\"page_token\":\"page2\"}"))
                        .willReturn(okJson("{\"vulns\":[{\"id\":\"GHSA-2\"}]}")));

        assertThat(new OsvClient(http, api).query(List.of(TEXT)))
                .containsEntry(TEXT, List.of("GHSA-1", "GHSA-2"));
    }

    @Test
    void query_sameBatchTwice_isServedFromTheCache() throws Exception {
        stubFor(post("/osv/v1/querybatch").willReturn(okJson("{\"results\":[{}]}")));
        OsvClient client = new OsvClient(http, api);

        client.query(List.of(TEXT));
        client.query(List.of(TEXT));

        verify(exactly(1), postRequestedFor(urlEqualTo("/osv/v1/querybatch")));
    }

    @Test
    void query_osvFailing_throws() {
        stubFor(post("/osv/v1/querybatch").willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> new OsvClient(http, api).query(List.of(TEXT)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    void vulnerability_recordedAdvisory_parsesAliasesSeverityAndRanges() throws Exception {
        RecordedOsv.stubAll();

        OsvVulnerability advisory =
                new OsvClient(http, URI.create(api.toString()))
                        .vulnerability("GHSA-599f-7c49-w659");

        assertThat(advisory.aliases()).containsExactly("CVE-2022-42889");
        assertThat(advisory.severity())
                .extracting(OsvVulnerability.Score::score)
                .containsExactly("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
        assertThat(advisory.databaseSeverity()).contains("CRITICAL");
        assertThat(FixedVersions.fixedVersion(advisory, TEXT.name(), "1.9")).contains("1.10.0");
        verify(exactly(1), getRequestedFor(urlEqualTo("/osv/v1/vulns/GHSA-599f-7c49-w659")));
    }

    @Test
    void vulnerability_malformedJson_throws() {
        stubFor(get("/osv/v1/vulns/GHSA-bad").willReturn(ok("{not json")));

        assertThatThrownBy(() -> new OsvClient(http, api).vulnerability("GHSA-bad"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Malformed OSV advisory");
    }
}
