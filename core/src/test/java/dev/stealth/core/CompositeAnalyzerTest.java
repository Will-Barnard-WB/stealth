package dev.stealth.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompositeAnalyzerTest {

    private final RepoContext context = new RepoContext(Path.of("repo"));

    @Mock private Analyzer first;

    @Mock private Analyzer second;

    @Test
    void analyze_twoAnalyzers_returnsFindingsFromBothInOrder() {
        Finding outdated = new Finding("deps/outdated", "jackson-databind is outdated");
        Finding secret = new Finding("secrets/aws-access-key", "AWS key in config.yml");
        when(first.analyze(context)).thenReturn(List.of(outdated));
        when(second.analyze(context)).thenReturn(List.of(secret));

        List<Finding> findings = new CompositeAnalyzer(List.of(first, second)).analyze(context);

        assertThat(findings).containsExactly(outdated, secret);
    }

    @Test
    void analyze_noAnalyzers_returnsNoFindings() {
        List<Finding> findings = new CompositeAnalyzer(List.of()).analyze(context);

        assertThat(findings).isEmpty();
    }
}
