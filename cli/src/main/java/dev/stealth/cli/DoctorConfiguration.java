package dev.stealth.cli;

import dev.stealth.core.Analyzer;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.clean.Cleaner;
import dev.stealth.core.clean.CleanupVerifier;
import dev.stealth.core.clean.PatchPlanner;
import dev.stealth.core.deps.DependencyFreshnessAnalyzer;
import dev.stealth.core.deps.MaintenanceAnalyzer;
import dev.stealth.core.deps.MavenCentralClient;
import dev.stealth.core.deps.MavenCentralSearch;
import dev.stealth.core.duplication.DuplicationAnalyzer;
import dev.stealth.core.eol.EndOfLifeAnalyzer;
import dev.stealth.core.eol.EndOfLifeClient;
import dev.stealth.core.http.CachedHttpClient;
import dev.stealth.core.http.HttpCache;
import dev.stealth.core.hygiene.RepoHygieneAnalyzer;
import dev.stealth.core.impact.TestGaps;
import dev.stealth.core.impact.UpgradeImpact;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenResolverSettings;
import dev.stealth.core.secrets.SecretsAnalyzer;
import dev.stealth.core.vuln.OsvClient;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import java.time.Clock;
import java.time.Duration;
import java.time.Period;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({DuplicationProperties.class, HygieneProperties.class})
class DoctorConfiguration {

    /** Runs every {@link Analyzer} bean. */
    @Bean
    AnalyzerRunner analyzerRunner(
            ObjectProvider<Analyzer> analyzers,
            @Value("${stealth.doctor.analyzer-timeout:60s}") Duration timeout) {
        return new AnalyzerRunner(analyzers.orderedStream().toList(), timeout);
    }

    /** Shared by every analyzer in a run through {@code RepoContext.get}. */
    @Bean
    MavenModelLoader mavenModelLoader(OfflineMode offlineMode) {
        return new MavenModelLoader(
                () -> MavenResolverSettings.defaults().withOffline(offlineMode.isOffline()));
    }

    @Bean
    CachedHttpClient cachedHttpClient(OfflineMode offlineMode) {
        return CachedHttpClient.create(HttpCache.defaultCache(), offlineMode::isOffline);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    MavenCentralClient mavenCentralClient(CachedHttpClient http) {
        return new MavenCentralClient(http, MavenResolverSettings.MAVEN_CENTRAL);
    }

    @Bean
    DependencyFreshnessAnalyzer dependencyFreshnessAnalyzer(
            MavenModelLoader loader, MavenCentralClient central) {
        return new DependencyFreshnessAnalyzer(loader, central);
    }

    @Bean
    OsvClient osvClient(CachedHttpClient http) {
        return new OsvClient(http, OsvClient.OSV);
    }

    @Bean
    VulnerabilityAnalyzer vulnerabilityAnalyzer(MavenModelLoader loader, OsvClient osv) {
        return new VulnerabilityAnalyzer(loader, osv);
    }

    @Bean
    MavenCentralSearch mavenCentralSearch(CachedHttpClient http) {
        return new MavenCentralSearch(http, MavenCentralSearch.SEARCH);
    }

    /** {@code stealth.maintenance.stale-after} until {@code .stealth.yml} can set it per repo. */
    @Bean
    MaintenanceAnalyzer maintenanceAnalyzer(
            MavenModelLoader loader,
            MavenCentralClient central,
            MavenCentralSearch search,
            @Value("${stealth.maintenance.stale-after:P2Y}") Period staleAfter) {
        return new MaintenanceAnalyzer(loader, central, search, staleAfter, Clock.systemUTC());
    }

    @Bean
    EndOfLifeClient endOfLifeClient(CachedHttpClient http) {
        return new EndOfLifeClient(http, EndOfLifeClient.ENDOFLIFE_DATE);
    }

    @Bean
    EndOfLifeAnalyzer endOfLifeAnalyzer(
            MavenModelLoader loader, EndOfLifeClient endOfLife, Clock clock) {
        return new EndOfLifeAnalyzer(loader, endOfLife, clock);
    }

    /** {@code stealth clean}: proven patches, applied on a branch, and verification. */
    @Bean
    PatchPlanner patchPlanner(MavenModelLoader loader, VulnerabilityAnalyzer vulnerabilities) {
        return new PatchPlanner(loader, vulnerabilities);
    }

    @Bean
    Cleaner cleaner(PatchPlanner planner, Clock clock) {
        return new Cleaner(planner, clock);
    }

    @Bean
    CleanupVerifier cleanupVerifier(AnalyzerRunner runner) {
        return new CleanupVerifier(runner);
    }

    @Bean
    UpgradeImpact upgradeImpact(MavenModelLoader loader) {
        return new UpgradeImpact(loader);
    }

    @Bean
    TestGaps testGaps(MavenModelLoader loader, UpgradeImpact impact) {
        return new TestGaps(loader, impact);
    }

    @Bean
    SecretsAnalyzer secretsAnalyzer(Clock clock) {
        return new SecretsAnalyzer(clock);
    }

    @Bean
    DuplicationAnalyzer duplicationAnalyzer(DuplicationProperties properties) {
        return new DuplicationAnalyzer(properties.minTokens(), properties.includeTests());
    }

    @Bean
    RepoHygieneAnalyzer repoHygieneAnalyzer(
            MavenModelLoader loader, HygieneProperties properties, Clock clock) {
        return new RepoHygieneAnalyzer(
                loader, properties.staleBranchAfter(), properties.largeFileBytes(), clock);
    }
}
