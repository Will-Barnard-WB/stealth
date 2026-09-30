package dev.stealth.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import picocli.CommandLine.Help.Ansi;

class StealthCliTest {

    @Test
    void glyphs_utf8Stdout_usesTheFullSet() {
        assertThat(StealthCli.glyphs(StandardCharsets.UTF_8)).isEqualTo(Banner.Glyphs.UNICODE);
    }

    @Test
    void glyphs_windowsOemCodePage_keepsTheLogoButDropsTheUnicodeMarkers() {
        // The default on a Windows console in much of the world: 437 in the US, 850 in Europe
        assertThat(StealthCli.glyphs(Charset.forName("IBM437"))).isEqualTo(Banner.Glyphs.CODE_PAGE);
        assertThat(StealthCli.glyphs(Charset.forName("IBM850"))).isEqualTo(Banner.Glyphs.CODE_PAGE);
    }

    @Test
    void glyphs_latin1CodePage_fallsBackToAscii() {
        assertThat(StealthCli.glyphs(Charset.forName("windows-1252")))
                .isEqualTo(Banner.Glyphs.ASCII);
        assertThat(StealthCli.glyphs(StandardCharsets.US_ASCII)).isEqualTo(Banner.Glyphs.ASCII);
    }

    @Test
    void glyphs_matchesWhateverStdoutCanEncode() {
        assertThat(StealthCli.glyphs()).isEqualTo(StealthCli.glyphs(System.out.charset()));
    }

    @Test
    void ansi_stdoutIsNotATerminal_staysOff() {
        // Tests run with stdout redirected, so neither picocli nor the Windows fallback should
        // decide to emit escape codes
        assertThat(StealthCli.ansi()).isEqualTo(Ansi.OFF);
    }
}
