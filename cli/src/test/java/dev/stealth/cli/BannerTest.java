package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

class BannerTest {

    @Test
    void top_ansiDisabled_printsPlainTitle() {
        String top = Banner.top(Ansi.OFF, details(Banner.Glyphs.UNICODE));

        assertThat(top.lines()).containsExactly("stealth 1.2.3", "Tagline.");
    }

    @Test
    void top_ansiEnabled_printsWelcomeBoxWithVersionAndDirectory() {
        String top = withoutEscapes(Banner.top(Ansi.ON, details(Banner.Glyphs.UNICODE)));

        assertThat(top)
                .contains("✻ Welcome to stealth!", "Tagline.", "version: 1.2.3", "cwd: /work/repo")
                .contains("Getting started:", "stealth doctor <path>");
    }

    @Test
    void top_ansiEnabled_logoAndBoxRowsHaveTheSameWidth() {
        List<String> rows =
                withoutEscapes(Banner.top(Ansi.ON, details(Banner.Glyphs.UNICODE)))
                        .lines()
                        .skip(1)
                        .limit(Banner.LOGO_ROWS + 1 + 8)
                        .filter(line -> !line.isEmpty())
                        .toList();

        assertThat(rows).hasSize(Banner.LOGO_ROWS + 8);
        assertThat(rows).extracting(String::length).containsOnly(Banner.WIDTH);
    }

    @Test
    void top_codePageStdout_usesCodePageSafeBoxAndKeepsTheLogo() {
        String top = withoutEscapes(Banner.top(Ansi.ON, details(Banner.Glyphs.CODE_PAGE)));

        assertThat(top)
                .contains("┌", "─", "│", "* Welcome to stealth!", "███████╗")
                .doesNotContain("╭", "✻", "…", "※");
    }

    @Test
    void top_asciiOnlyStdout_dropsTheLogoAndDrawsThirdBoxInAscii() {
        String top = withoutEscapes(Banner.top(Ansi.ON, details(Banner.Glyphs.ASCII)));

        assertThat(top)
                .contains("+------", "| * Welcome to stealth!", "Getting started:")
                .doesNotContain("█", "╗", "─", "│", "┌", "╭", "✻");
    }

    @Test
    void bottom_asciiOnlyStdout_printsTipWithoutUnicodeMarker() {
        assertThat(withoutEscapes(Banner.bottom(Ansi.ON, details(Banner.Glyphs.ASCII))))
                .contains("* Tip: ")
                .doesNotContain("※");
    }

    @Test
    void glyphs_everySetIsPrintableByAnEncodingThatClaimsIt() {
        assertThat(Charset.forName("UTF-8").newEncoder().canEncode(Banner.Glyphs.UNICODE.sample()))
                .isTrue();
        assertThat(
                        Charset.forName("IBM850")
                                .newEncoder()
                                .canEncode(Banner.Glyphs.CODE_PAGE.sample()))
                .isTrue();
        assertThat(Charset.forName("US-ASCII").newEncoder().canEncode(Banner.Glyphs.ASCII.sample()))
                .isTrue();
    }

    @Test
    void glyphs_richerSetsAreRejectedByTheEncodingsTheyOutgrow() {
        assertThat(Charset.forName("IBM850").newEncoder().canEncode(Banner.Glyphs.UNICODE.sample()))
                .isFalse();
        assertThat(
                        Charset.forName("windows-1252")
                                .newEncoder()
                                .canEncode(Banner.Glyphs.CODE_PAGE.sample()))
                .isFalse();
    }

    @Test
    void bottom_ansiEnabled_printsATip() {
        assertThat(withoutEscapes(Banner.bottom(Ansi.ON, details(Banner.Glyphs.UNICODE))))
                .contains("※ Tip: ");
    }

    @Test
    void fit_longPath_keepsTheEndBehindAnEllipsis() {
        assertThat(Banner.fit("/very/long/path/to/my-app", 12, "…")).isEqualTo("…h/to/my-app");
    }

    private static Banner.Details details(Banner.Glyphs glyphs) {
        return new Banner.Details("1.2.3", "Tagline.", "/work/repo", glyphs);
    }

    private static String withoutEscapes(String text) {
        return text.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
