package dev.stealth.cli;

import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code stealth.hygiene.*} until {@code .stealth.yml} can set them per repo. */
@ConfigurationProperties("stealth.hygiene")
record HygieneProperties(
        @DefaultValue("P90D") Period staleBranchAfter,
        @DefaultValue("5242880") long largeFileBytes) {}
