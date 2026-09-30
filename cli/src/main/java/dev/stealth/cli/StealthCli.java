package dev.stealth.cli;

import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_OPTION_LIST;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_OPTION_LIST_HEADING;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_SYNOPSIS;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING;

import java.io.Console;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.List;
import java.util.Locale;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Help.Ansi.Style;
import picocli.CommandLine.Help.ColorScheme;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.UnmatchedArgumentException;

/** Help layout, colours and error output shared by the stealth command and its subcommands. */
final class StealthCli {

    static final String SECTION_KEY_BANNER = "banner";

    static final String SECTION_KEY_OUTRO = "outro";

    private StealthCli() {}

    static CommandLine configure(CommandLine commandLine) {
        return configure(commandLine, ansi(), glyphs());
    }

    static CommandLine configure(CommandLine commandLine, Ansi ansi, Banner.Glyphs glyphs) {
        commandLine.setOut(writerFor(System.out));
        commandLine.setErr(writerFor(System.err));
        commandLine.setColorScheme(colorScheme(ansi));
        commandLine.setParameterExceptionHandler(StealthCli::handleParameterException);
        applyHeadings(commandLine.getCommandSpec());

        // The root command opens with the logo and welcome box, lists commands before options and
        // ends with a tip
        CommandSpec root = commandLine.getCommandSpec();
        Banner.Details details =
                new Banner.Details(
                        ManifestVersionProvider.version(),
                        root.usageMessage().description()[0],
                        System.getProperty("user.dir"),
                        glyphs);
        root.usageMessage()
                .sectionMap()
                .put(SECTION_KEY_BANNER, help -> Banner.top(help.colorScheme().ansi(), details));
        root.usageMessage()
                .sectionMap()
                .put(SECTION_KEY_OUTRO, help -> Banner.bottom(help.colorScheme().ansi(), details));
        root.usageMessage()
                .sectionKeys(
                        List.of(
                                SECTION_KEY_BANNER,
                                SECTION_KEY_SYNOPSIS_HEADING,
                                SECTION_KEY_SYNOPSIS,
                                SECTION_KEY_COMMAND_LIST_HEADING,
                                SECTION_KEY_COMMAND_LIST,
                                SECTION_KEY_OPTION_LIST_HEADING,
                                SECTION_KEY_OPTION_LIST,
                                SECTION_KEY_OUTRO));
        return commandLine;
    }

    static ColorScheme colorScheme(Ansi ansi) {
        return new ColorScheme.Builder(ansi)
                .commands(Style.parse("bold," + Banner.ACCENT))
                .options(Style.parse("fg(214)"))
                .parameters(Style.parse("fg(214)"))
                .optionParams(Style.italic)
                .errors(Style.parse("bold,fg(203)"))
                .stackTraces(Style.italic)
                .build();
    }

    private static void applyHeadings(CommandSpec spec) {
        spec.usageMessage()
                .synopsisHeading(heading("Usage:") + " ")
                .descriptionHeading("%n")
                .parameterListHeading(heading("Arguments:") + "%n")
                .optionListHeading(heading("Options:") + "%n")
                .commandListHeading(heading("Commands:") + "%n");
        spec.subcommands().values().forEach(sub -> applyHeadings(sub.getCommandSpec()));
    }

    private static String heading(String text) {
        return "%n@|bold," + Banner.ACCENT + " " + text + "|@";
    }

    /**
     * Whether to colour the output. picocli's {@link Ansi#AUTO} says no on Windows unless Jansi is
     * on the classpath or the shell looks like Cygwin, so PowerShell, cmd.exe and Windows Terminal
     * lose the banner even though every console host since Windows 10 understands ANSI escapes.
     * Where picocli already says yes -- macOS, Linux, Git Bash -- its answer stands, along with the
     * NO_COLOR, CLICOLOR, ConEmuANSI and picocli.ansi overrides it honours.
     */
    static Ansi ansi() {
        if (Ansi.AUTO.enabled() || ansiSupportedOnWindows()) {
            return Ansi.ON;
        }
        return Ansi.OFF;
    }

    /**
     * Whether this is the Windows console picocli gives up on but that does understand ANSI: output
     * has to be going to a console rather than a pipe, and the user must not have asked for plain
     * output.
     */
    private static boolean ansiSupportedOnWindows() {
        return isWindows10OrLater() && stdoutIsConsole() && !plainOutputRequested();
    }

    private static boolean isWindows10OrLater() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return false;
        }
        // Windows 10 and 11 both report 10.0; 8.1 and earlier report 6.x and predate ANSI support
        try {
            String major = System.getProperty("os.version", "").split("\\.")[0];
            return Integer.parseInt(major) >= 10;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Whether stdout is a console rather than a pipe or a file. A non-null {@link System#console()}
     * means that on Java 21, but from Java 22 one comes back for redirected output too, so use
     * {@code Console.isTerminal()} when the runtime has it.
     */
    private static boolean stdoutIsConsole() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            return (boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    /** The ways of asking for plain output that picocli would have checked for us. */
    private static boolean plainOutputRequested() {
        return System.getProperty("picocli.ansi") != null
                || System.getenv("NO_COLOR") != null
                || "0".equals(System.getenv("CLICOLOR"))
                || "OFF".equals(System.getenv("ConEmuANSI"));
    }

    /**
     * A writer that encodes the way the stream itself says it will. picocli's default writers wrap
     * {@code System.out} with {@link Charset#defaultCharset()}, which has been UTF-8 since Java 18
     * whatever the terminal is set to; on a Windows console, whose code page is rarely UTF-8, that
     * turns the box drawing into mojibake.
     */
    private static PrintWriter writerFor(PrintStream stream) {
        return new PrintWriter(new OutputStreamWriter(stream, stream.charset()), true);
    }

    /** The richest set of characters stdout can actually print. */
    static Banner.Glyphs glyphs() {
        return glyphs(System.out.charset());
    }

    static Banner.Glyphs glyphs(Charset charset) {
        CharsetEncoder encoder = charset.newEncoder();
        for (Banner.Glyphs candidate : Banner.Glyphs.BY_RICHNESS) {
            if (encoder.canEncode(candidate.sample())) {
                return candidate;
            }
        }
        return Banner.Glyphs.ASCII;
    }

    /**
     * A short error, a suggestion for typos, and a pointer to --help, instead of the full usage.
     */
    private static int handleParameterException(ParameterException ex, String[] args) {
        CommandLine commandLine = ex.getCommandLine();
        PrintWriter err = commandLine.getErr();
        ColorScheme colors = commandLine.getColorScheme();

        err.println(colors.errorText(ex.getMessage()));
        UnmatchedArgumentException.printSuggestions(ex, err);
        err.println(
                colors.ansi()
                        .string(
                                "Run '@|bold "
                                        + commandLine.getCommandSpec().qualifiedName()
                                        + " --help|@' to see what's available."));
        err.flush();
        return commandLine.getCommandSpec().exitCodeOnInvalidInput();
    }
}
