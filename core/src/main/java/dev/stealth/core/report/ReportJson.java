package dev.stealth.core.report;

import dev.stealth.core.Finding;
import java.util.Locale;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Shared by the JSON and SARIF renderers: one mapper, and output identical on every OS. */
final class ReportJson {

    static final JsonMapper JSON = JsonMapper.builder().build();

    // Two-space indent and "\n" everywhere, so the output is byte-identical on Windows
    private static final DefaultPrettyPrinter PRETTY =
            new DefaultPrettyPrinter(
                            Separators.createDefaultInstance()
                                    .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                                    .withObjectEmptySeparator("")
                                    .withArrayEmptySeparator(""))
                    .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private ReportJson() {}

    static ObjectNode object() {
        return JSON.createObjectNode();
    }

    static String write(JsonNode node) {
        return JSON.writer().with(PRETTY).writeValueAsString(node) + "\n";
    }

    static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The advisory's summary from a vulnerability finding's message, which reads {@code <component>
     * <version>: <id> (<cve>) <summary>; fixed in ...}.
     */
    static String advisorySummary(Finding finding) {
        String message = finding.message();
        String id = finding.advisory().map(a -> a.id()).orElse("");
        int start = id.isEmpty() ? -1 : message.indexOf(id);
        if (start < 0) {
            return message;
        }
        String rest =
                message.substring(start + id.length()).replaceFirst("^ \\(CVE-[^)]*\\)", "").trim();
        int end = rest.indexOf("; ");
        String summary = end < 0 ? rest : rest.substring(0, end);
        return summary.isBlank() ? id : summary;
    }
}
