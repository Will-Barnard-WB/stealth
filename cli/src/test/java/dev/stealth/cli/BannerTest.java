package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

class BannerTest {

    @Test
    void top_ansiDisabled_printsPlainTitle() {
        String top = Banner.top(Ansi.OFF, details(true));

        assertThat(top.lines()).containsExactly("stealth 1.2.3", "Tagline.");
    }

    @Test
    void top_ansiEnabled_printsWelcomeBoxWithVersionAndDirectory() {
        String top = withoutEscapes(Banner.top(Ansi.ON, details(true)));

        assertThat(top)
                .contains("✻ Welcome to stealth!", "Tagline.", "version: 1.2.3", "cwd: /work/repo")
                .contains("Getting started:", "stealth doctor <path>");
    }

    @Test
    void top_ansiEnabled_logoAndBoxRowsHaveTheSameWidth() {
        List<String> rows =
                withoutEscapes(Banner.top(Ansi.ON, details(true)))
                        .lines()
                        .skip(1)
                        .limit(Banner.LOGO_ROWS + 1 + 8)
                        .filter(line -> !line.isEmpty())
                        .toList();

        assertThat(rows).hasSize(Banner.LOGO_ROWS + 8);
        assertThat(rows).extracting(String::length).containsOnly(Banner.WIDTH);
    }

    @Test
    void top_stdoutWithoutUnicode_usesCodePageSafeBox() {
        String top = withoutEscapes(Banner.top(Ansi.ON, details(false)));

        assertThat(top).contains("┌", "* Welcome to stealth!").doesNotContain("╭", "✻");
    }

    @Test
    void bottom_ansiEnabled_printsATip() {
        assertThat(withoutEscapes(Banner.bottom(Ansi.ON, details(true)))).contains("※ Tip: ");
    }

    @Test
    void fit_longPath_keepsTheEndBehindAnEllipsis() {
        assertThat(Banner.fit("/very/long/path/to/my-app", 12, "…")).isEqualTo("…h/to/my-app");
    }

    private static Banner.Details details(boolean unicode) {
        return new Banner.Details("1.2.3", "Tagline.", "/work/repo", unicode);
    }

    private static String withoutEscapes(String text) {
        return text.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
