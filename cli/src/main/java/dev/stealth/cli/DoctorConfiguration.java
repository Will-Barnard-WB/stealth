package dev.stealth.cli;

import dev.stealth.core.Analyzer;
import dev.stealth.core.AnalyzerRunner;
import dev.stealth.core.deps.DependencyFreshnessAnalyzer;
import dev.stealth.core.deps.MavenCentralClient;
import dev.stealth.core.http.CachedHttpClient;
import dev.stealth.core.http.HttpCache;
import dev.stealth.core.maven.MavenModelLoader;
import dev.stealth.core.maven.MavenResolverSettings;
import dev.stealth.core.vuln.OsvClient;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
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
}
