package dev.stealth.core;

import dev.stealth.core.deps.DependencyFreshnessAnalyzer;
import dev.stealth.core.deps.MaintenanceAnalyzer;
import dev.stealth.core.deps.MavenCentralClient;
import dev.stealth.core.deps.MavenCentralSearch;
import dev.stealth.core.deps.RecordedCentral;
import dev.stealth.core.duplication.DuplicationAnalyzer;
import dev.stealth.core.eol.EndOfLifeAnalyzer;
import dev.stealth.core.eol.EndOfLifeClient;
import dev.stealth.core.eol.RecordedEndOfLife;
import dev.stealth.core.http.CachedHttpClient;
import dev.stealth.core.http.HttpCache;
import dev.stealth.core.hygiene.RepoHygieneAnalyzer;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.secrets.SecretsAnalyzer;
import dev.stealth.core.vuln.OsvClient;
import dev.stealth.core.vuln.RecordedOsv;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Every analyzer, against recorded Maven Central, OSV.dev and endoflife.date responses served by
 * WireMock, with the clock pinned to the date the recordings were made. Call from a
 * {@code @WireMockTest} class.
 */
public final class AllAnalyzers {

    public static final Clock REFERENCE_DATE =
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);

    private AllAnalyzers() {}

    public static AnalyzerRunner runner(
            String wireMockBase, Path cacheDirectory, MavenModelLoader loader) {
        RecordedCentral.stubAll();
        RecordedOsv.stubAll();
        RecordedEndOfLife.stubAll();
        CachedHttpClient http =
                new CachedHttpClient(
                        HttpClient.newHttpClient(),
                        new HttpCache(cacheDirectory, Duration.ofHours(24), Clock.systemUTC()),
                        () -> false,
                        1,
                        Duration.ZERO);
        MavenCentralClient central =
                new MavenCentralClient(http, URI.create(wireMockBase + RecordedCentral.BASE_PATH));
        return new AnalyzerRunner(
                List.of(
                        new DependencyFreshnessAnalyzer(loader, central),
                        new VulnerabilityAnalyzer(
                                loader,
                                new OsvClient(
                                        http, URI.create(wireMockBase + RecordedOsv.BASE_PATH))),
                        new MaintenanceAnalyzer(
                                loader,
                                central,
                                new MavenCentralSearch(
                                        http,
                                        URI.create(wireMockBase + RecordedCentral.SEARCH_PATH)),
                                MaintenanceAnalyzer.DEFAULT_STALE_AFTER,
                                REFERENCE_DATE),
                        new EndOfLifeAnalyzer(
                                loader,
                                new EndOfLifeClient(
                                        http,
                                        URI.create(wireMockBase + RecordedEndOfLife.BASE_PATH)),
                                REFERENCE_DATE),
                        new SecretsAnalyzer(REFERENCE_DATE),
                        new DuplicationAnalyzer(),
                        new RepoHygieneAnalyzer(
                                loader,
                                RepoHygieneAnalyzer.DEFAULT_STALE_BRANCH_AFTER,
                                RepoHygieneAnalyzer.DEFAULT_LARGE_FILE_BYTES,
                                REFERENCE_DATE)),
                Duration.ofSeconds(120));
    }
}
