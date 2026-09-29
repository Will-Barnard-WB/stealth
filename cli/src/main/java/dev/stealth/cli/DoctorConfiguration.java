package dev.stealth.cli;

import dev.stealth.core.Analyzer;
import dev.stealth.core.AnalyzerRunner;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DoctorConfiguration {

    /** Runs every {@link Analyzer} bean. There may be none until the analyzer tickets land. */
    @Bean
    AnalyzerRunner analyzerRunner(
            ObjectProvider<Analyzer> analyzers,
            @Value("${stealth.doctor.analyzer-timeout:60s}") Duration timeout) {
        return new AnalyzerRunner(analyzers.orderedStream().toList(), timeout);
    }
}
