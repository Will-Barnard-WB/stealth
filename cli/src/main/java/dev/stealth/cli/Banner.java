package dev.stealth.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import picocli.CommandLine.Help.Ansi;

/**
 * The top and bottom of {@code stealth} and {@code stealth --help}. On a colour terminal: the logo,
 * a welcome box, getting-started steps and a tip. Otherwise a plain title and a pointer to --help,
 * so piped output and dumb terminals don't get escape codes or box drawing.
 */
final class Banner {

    static final int LOGO_ROWS = 6;

    /** Width of the logo, which the welcome box matches. */
    static final int WIDTH = 58;

    /** Coral, used for headings, commands and the box border (xterm-256 colour). */
    static final String ACCENT = "fg(209)";

    static final String MUTED = "fg(245)";

    private static final String WORD = "STEALTH";

    /** The distinct characters the logo is drawn with, for {@link Glyphs#sample()}. */
    private static final String LOGO_CHARS = "█╗╔═╝║╚";

    // "ANSI Shadow" letters. Every row of a letter has the same width so the columns line up.
    private static final Map<Character, String[]> LETTERS =
            Map.of(
                    'S',
                    new String[] {
                        "███████╗", "██╔════╝", "███████╗", "╚════██║", "███████║", "╚══════╝"
                    },
                    'T',
                    new String[] {
                        "████████╗", "╚══██╔══╝", "   ██║   ", "   ██║   ", "   ██║   ", "   ╚═╝   "
                    },
                    'E',
                    new String[] {
                        "███████╗", "██╔════╝", "█████╗  ", "██╔══╝  ", "███████╗", "╚══════╝"
                    },
                    'A',
                    new String[] {
                        " █████╗ ", "██╔══██╗", "███████║", "██╔══██║", "██║  ██║", "╚═╝  ╚═╝"
                    },
                    'L',
                    new String[] {
                        "██╗     ", "██║     ", "██║     ", "██║     ", "███████╗", "╚══════╝"
                    },
                    'H',
                    new String[] {
                        "██╗  ██╗", "██║  ██║", "███████║", "██╔══██║", "██║  ██║", "╚═╝  ╚═╝"
                    });

    // Sunset gradient from amber through coral to pink, one colour per letter of STEALTH
    private static final int[] GRADIENT = {214, 208, 209, 203, 204, 205, 206};

    private static final List<String> TIPS =
            List.of(
                    "Check a repository without cd-ing into it: stealth doctor ~/code/my-app",
                    "Mistyped a command? stealth suggests the closest match",
                    "stealth -V prints the version you're running",
                    "Every command has --help, including stealth doctor --help");

    /** What the welcome box shows, and how much of it stdout's encoding can print. */
    record Details(String version, String tagline, String cwd, Glyphs glyphs) {}

    /**
     * The characters the banner is drawn with. A Windows console runs in a code page rather than
     * UTF-8 unless it is asked otherwise, so the drawing has to degrade rather than turn into
     * question marks: the OEM code pages (437, 850, 852) carry box drawing and the blocks the logo
     * is built from, but not ✻ or ╭, and a Latin-1 code page carries none of them.
     */
    record Glyphs(
            String topLeft,
            String topRight,
            String bottomLeft,
            String bottomRight,
            String horizontal,
            String vertical,
            String star,
            String ellipsis,
            String tip,
            boolean logo) {

        static final Glyphs UNICODE = new Glyphs("╭", "╮", "╰", "╯", "─", "│", "✻", "…", "※", true);

        static final Glyphs CODE_PAGE =
                new Glyphs("┌", "┐", "└", "┘", "─", "│", "*", "...", "*", true);

        /** No box drawing and no blocks, so no logo either. */
        static final Glyphs ASCII =
                new Glyphs("+", "+", "+", "+", "-", "|", "*", "...", "*", false);

        /** Richest first, so the best set an encoding supports is the first one that fits. */
        static final List<Glyphs> BY_RICHNESS = List.of(UNICODE, CODE_PAGE, ASCII);

        /** Every character this set prints, so an encoding can be asked whether it has them. */
        String sample() {
            return topLeft
                    + topRight
                    + bottomLeft
                    + bottomRight
                    + horizontal
                    + vertical
                    + star
                    + ellipsis
                    + tip
                    + (logo ? LOGO_CHARS : "");
        }
    }

    private Banner() {}

    static String top(Ansi ansi, Details details) {
        String nl = System.lineSeparator();
        if (!ansi.enabled()) {
            return "stealth " + details.version() + nl + details.tagline() + nl;
        }
        List<String> lines = new ArrayList<>();
        lines.add("");
        if (details.glyphs().logo()) {
            lines.addAll(logo(ansi));
            lines.add("");
        }
        lines.addAll(welcomeBox(ansi, details));
        lines.add("");
        lines.addAll(gettingStarted(ansi));
        return String.join(nl, lines) + nl;
    }

    static String bottom(Ansi ansi, Details details) {
        String nl = System.lineSeparator();
        if (!ansi.enabled()) {
            return nl + "Run 'stealth <command> --help' for more on a command." + nl;
        }
        String tip = TIPS.get(ThreadLocalRandom.current().nextInt(TIPS.size()));
        return nl
                + ansi.string("@|" + MUTED + " " + details.glyphs().tip() + " Tip: " + tip + "|@")
                + nl;
    }

    private static List<String> logo(Ansi ansi) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < LOGO_ROWS; row++) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < WORD.length(); i++) {
                String letterRow = LETTERS.get(WORD.charAt(i))[row];
                line.append("@|fg(")
                        .append(GRADIENT[i])
                        .append(") ")
                        .append(letterRow)
                        .append("|@");
            }
            rows.add(ansi.string(line.toString()));
        }
        return rows;
    }

    private static List<String> welcomeBox(Ansi ansi, Details details) {
        Glyphs glyphs = details.glyphs();
        int contentWidth = WIDTH - 4;
        String tagline = fit(details.tagline(), contentWidth - 2, glyphs.ellipsis());
        String cwd = fit(details.cwd(), contentWidth - "  cwd: ".length(), glyphs.ellipsis());

        // Each row is {plain text, markup}; the plain text sets the padding
        List<String[]> rows =
                List.of(
                        new String[] {
                            glyphs.star() + " Welcome to stealth!",
                            "@|"
                                    + ACCENT
                                    + " "
                                    + glyphs.star()
                                    + "|@ Welcome to @|bold,"
                                    + ACCENT
                                    + " stealth|@!"
                        },
                        new String[] {"", ""},
                        new String[] {"  " + tagline, "  @|bold " + tagline + "|@"},
                        new String[] {"", ""},
                        new String[] {
                            "  version: " + details.version(),
                            "  @|" + MUTED + " version: " + details.version() + "|@"
                        },
                        new String[] {"  cwd: " + cwd, "  @|" + MUTED + " cwd: " + cwd + "|@"});

        String border = glyphs.horizontal().repeat(WIDTH - 2);
        String side = ansi.string("@|" + ACCENT + " " + glyphs.vertical() + "|@");
        List<String> lines = new ArrayList<>();
        lines.add(
                ansi.string(
                        "@|"
                                + ACCENT
                                + " "
                                + glyphs.topLeft()
                                + border
                                + glyphs.topRight()
                                + "|@"));
        for (String[] row : rows) {
            String padding = " ".repeat(contentWidth - row[0].length());
            lines.add(side + " " + ansi.string(row[1]) + padding + " " + side);
        }
        lines.add(
                ansi.string(
                        "@|"
                                + ACCENT
                                + " "
                                + glyphs.bottomLeft()
                                + border
                                + glyphs.bottomRight()
                                + "|@"));
        return lines;
    }

    private static List<String> gettingStarted(Ansi ansi) {
        String number = "@|" + MUTED + " %d.|@ ";
        return List.of(
                ansi.string("@|bold," + ACCENT + " Getting started:|@"),
                ansi.string(
                        "  "
                                + number.formatted(1)
                                + "Run @|bold,"
                                + ACCENT
                                + " stealth doctor|@ inside a repository to check it"),
                ansi.string(
                        "  "
                                + number.formatted(2)
                                + "Point it elsewhere with @|bold,"
                                + ACCENT
                                + " stealth doctor <path>|@"),
                ansi.string(
                        "  "
                                + number.formatted(3)
                                + "Add @|bold,"
                                + ACCENT
                                + " --help|@ to any command to see its options"));
    }

    /** Shortens {@code text} from the start, keeping the end (the most specific part of a path). */
    static String fit(String text, int width, String ellipsis) {
        if (text.length() <= width) {
            return text;
        }
        return ellipsis + text.substring(text.length() - (width - ellipsis.length()));
    }
}
