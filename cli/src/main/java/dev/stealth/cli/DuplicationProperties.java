package dev.stealth.cli;

import dev.stealth.core.duplication.DuplicationAnalyzer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** {@code stealth.duplication.*} until {@code .stealth.yml} can set them per repo. */
@ConfigurationProperties("stealth.duplication")
record DuplicationProperties(
        @DefaultValue("" + DuplicationAnalyzer.DEFAULT_MIN_TOKENS) int minTokens,
        @DefaultValue("false") boolean includeTests) {}
