package dev.stealth.mcp;

import dev.stealth.core.check.DependencyCheck;
import dev.stealth.core.deps.MavenCentralClient;
import dev.stealth.core.deps.MavenCentralSearch;
import dev.stealth.core.vuln.VulnerabilityAnalyzer;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.core.Ordered;

/**
 * The MCP server's web context. It runs as a child of the CLI's context, which provides the
 * analyzers and remote clients; started by {@link McpServer}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
public class McpServerApplication {

    @Bean
    DependencyCheck dependencyCheck(
            MavenCentralClient central,
            MavenCentralSearch search,
            VulnerabilityAnalyzer vulnerabilities,
            Clock clock) {
        return new DependencyCheck(central, search, vulnerabilities, clock);
    }

    /** Before everything else, including the MCP endpoint. */
    @Bean
    FilterRegistrationBean<LocalOnlyFilter> localOnlyFilter(
            @Value("${server.port}") int port, @Value("${stealth.mcp.token}") String token) {
        FilterRegistrationBean<LocalOnlyFilter> registration =
                new FilterRegistrationBean<>(new LocalOnlyFilter(port, token));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
