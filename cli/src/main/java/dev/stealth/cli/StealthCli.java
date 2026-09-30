package dev.stealth.cli;

import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_COMMAND_LIST_HEADING;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_OPTION_LIST;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_OPTION_LIST_HEADING;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_SYNOPSIS;
import static picocli.CommandLine.Model.UsageMessageSpec.SECTION_KEY_SYNOPSIS_HEADING;

import java.io.PrintWriter;
import java.nio.charset.Charset;
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
        return configure(commandLine, Ansi.AUTO);
    }

    static CommandLine configure(CommandLine commandLine, Ansi ansi) {
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
                        stdoutSupportsUnicode());
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

    /** Whether stdout's encoding can print ╭ and ✻, which the Windows console code pages can't. */
    static boolean stdoutSupportsUnicode() {
        String encoding = System.getProperty("stdout.encoding", Charset.defaultCharset().name());
        return encoding.toUpperCase(Locale.ROOT).startsWith("UTF");
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
