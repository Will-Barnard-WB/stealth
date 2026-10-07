package dev.stealth.core.clean;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Applies {@link PomEdit}s as text edits: the version on one line, or lines inserted into {@code
 * <properties>} or {@code <dependencyManagement>}, indented like the rest of the file and with its
 * line endings. Everything else in the POM stays byte for byte the same, so the diff is the fix and
 * nothing more.
 */
public final class PomEditor {

    private PomEditor() {}

    /**
     * Applies {@code edit} to the POM under {@code root}.
     *
     * @throws IOException if the POM can't be read or written, or doesn't look as the edit expects
     *     (the version isn't on the line exactly once, or the POM isn't well-formed)
     */
    public static void apply(Path root, PomEdit edit) throws IOException {
        Path pom = root.resolve(edit.pomPath());
        String text = Files.readString(pom, StandardCharsets.UTF_8);
        Files.writeString(pom, apply(text, edit), StandardCharsets.UTF_8);
    }

    /**
     * Applies several edits: line changes first, while every line number is still the one the edit
     * was planned against, then the insertions.
     */
    public static void applyAll(Path root, java.util.Collection<PomEdit> edits) throws IOException {
        List<PomEdit> ordered = new ArrayList<>(edits);
        ordered.sort(
                java.util.Comparator.comparingInt(
                        e ->
                                switch (e) {
                                    case PomEdit.SetVersion set -> 0;
                                    case PomEdit.SetProperty set -> 1;
                                    case PomEdit.PinVersion pin -> 2;
                                }));
        for (PomEdit edit : ordered) {
            apply(root, edit);
        }
    }

    static String apply(String text, PomEdit edit) throws IOException {
        Lines lines = Lines.of(text);
        switch (edit) {
            case PomEdit.SetVersion set -> setVersion(lines, set);
            case PomEdit.SetProperty set -> setProperty(lines, Structure.of(text), set);
            case PomEdit.PinVersion pin -> pinVersion(lines, Structure.of(text), pin);
        }
        return lines.text();
    }

    private static void setVersion(Lines lines, PomEdit.SetVersion edit) throws IOException {
        String line = lines.get(edit.line());
        String element = ">" + edit.from() + "<";
        int first = line.indexOf(element);
        if (first < 0 || line.indexOf(element, first + 1) >= 0) {
            throw new IOException(
                    edit.pomPath()
                            + ":"
                            + edit.line()
                            + " doesn't set version "
                            + edit.from()
                            + " exactly once: "
                            + line.strip());
        }
        lines.set(edit.line(), line.replace(element, ">" + edit.value() + "<"));
    }

    private static void setProperty(Lines lines, Structure pom, PomEdit.SetProperty edit)
            throws IOException {
        Optional<Integer> existing = pom.property(edit.name());
        if (existing.isPresent()) {
            String line = lines.get(existing.get());
            Matcher value =
                    Pattern.compile("(<" + Pattern.quote(edit.name()) + ">)([^<]*)(</)")
                            .matcher(line);
            if (!value.find()) {
                throw new IOException(
                        edit.pomPath()
                                + ":"
                                + existing.get()
                                + ": <"
                                + edit.name()
                                + "> isn't on one line");
            }
            lines.set(
                    existing.get(),
                    line.substring(0, value.start(2))
                            + edit.value()
                            + line.substring(value.end(2)));
            return;
        }
        String indent = lines.indent();
        String property = "<" + edit.name() + ">" + edit.value() + "</" + edit.name() + ">";
        if (pom.propertiesEnd > 0) {
            lines.insertBefore(pom.propertiesEnd, List.of(indent + indent + property));
        } else {
            lines.insertBefore(
                    pom.sectionInsertionLine(),
                    List.of(
                            indent + "<properties>",
                            indent + indent + property,
                            indent + "</properties>"));
        }
    }

    private static void pinVersion(Lines lines, Structure pom, PomEdit.PinVersion edit)
            throws IOException {
        String i = lines.indent();
        List<String> dependency =
                List.of(
                        "<dependency>",
                        i + "<groupId>" + edit.groupId() + "</groupId>",
                        i + "<artifactId>" + edit.artifactId() + "</artifactId>",
                        i + "<version>" + edit.value() + "</version>",
                        "</dependency>");
        if (pom.managedDependenciesEnd > 0) {
            lines.insertBefore(pom.managedDependenciesEnd, indented(i + i + i, dependency));
            return;
        }
        List<String> section = new ArrayList<>();
        section.add(i + "<dependencyManagement>");
        section.add(i + i + "<dependencies>");
        section.addAll(indented(i + i + i, dependency));
        section.add(i + i + "</dependencies>");
        section.add(i + "</dependencyManagement>");
        if (pom.dependencyManagementEnd > 0) {
            // <dependencyManagement> without <dependencies>: add them inside it
            lines.insertBefore(pom.dependencyManagementEnd, section.subList(1, section.size() - 1));
        } else {
            lines.insertBefore(pom.sectionInsertionLine(), section);
        }
    }

    private static List<String> indented(String indent, List<String> lines) {
        return lines.stream().map(l -> indent + l).toList();
    }

    /** Line numbers (1-based) of the parts of {@code <project>} the edits need. */
    private static final class Structure {
        int propertiesEnd;
        int dependencyManagementEnd;
        int managedDependenciesEnd;
        int dependenciesStart;
        int buildStart;
        int projectEnd;
        final java.util.Map<String, Integer> properties = new java.util.HashMap<>();

        Optional<Integer> property(String name) {
            return Optional.ofNullable(properties.get(name));
        }

        /**
         * Where a new top-level section goes: before {@code <dependencies>}, {@code <build>} or the
         * end.
         */
        int sectionInsertionLine() {
            if (dependenciesStart > 0) {
                return dependenciesStart;
            }
            return buildStart > 0 ? buildStart : projectEnd;
        }

        static Structure of(String text) throws IOException {
            Structure structure = new Structure();
            try {
                XMLInputFactory factory = XMLInputFactory.newFactory();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
                XMLStreamReader xml = factory.createXMLStreamReader(new StringReader(text));
                List<String> path = new ArrayList<>();
                while (xml.hasNext()) {
                    int event = xml.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        path.add(xml.getLocalName());
                        int line = xml.getLocation().getLineNumber();
                        String at = String.join("/", path);
                        if (at.equals("project/dependencies")) {
                            structure.dependenciesStart = line;
                        } else if (at.equals("project/build")) {
                            structure.buildStart = line;
                        } else if (path.size() == 3 && at.startsWith("project/properties/")) {
                            structure.properties.putIfAbsent(xml.getLocalName(), line);
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String at = String.join("/", path);
                        int line = xml.getLocation().getLineNumber();
                        switch (at) {
                            case "project/properties" -> structure.propertiesEnd = line;
                            case "project/dependencyManagement" ->
                                    structure.dependencyManagementEnd = line;
                            case "project/dependencyManagement/dependencies" ->
                                    structure.managedDependenciesEnd = line;
                            case "project" -> structure.projectEnd = line;
                            default -> {}
                        }
                        path.removeLast();
                    }
                }
            } catch (XMLStreamException e) {
                throw new IOException("not a well-formed POM: " + e.getMessage(), e);
            }
            if (structure.projectEnd == 0) {
                throw new IOException("not a POM: no <project> element");
            }
            return structure;
        }
    }

    /** The file as lines, remembering its line ending and whether it ended with one. */
    private static final class Lines {
        private final List<String> lines;
        private final String newline;
        private final boolean trailingNewline;

        private Lines(List<String> lines, String newline, boolean trailingNewline) {
            this.lines = lines;
            this.newline = newline;
            this.trailingNewline = trailingNewline;
        }

        static Lines of(String text) {
            String newline = text.contains("\r\n") ? "\r\n" : "\n";
            boolean trailing = text.endsWith("\n");
            List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
            if (trailing) {
                lines.removeLast();
            }
            return new Lines(lines, newline, trailing);
        }

        String get(int line) throws IOException {
            if (line < 1 || line > lines.size()) {
                throw new IOException("no line " + line);
            }
            return lines.get(line - 1);
        }

        void set(int line, String text) {
            lines.set(line - 1, text);
        }

        /** Inserts {@code added} above {@code line}, at that line's indentation level. */
        void insertBefore(int line, List<String> added) {
            lines.addAll(line - 1, added);
        }

        /** One level of indentation as the file uses it (four spaces if it can't tell). */
        String indent() {
            for (String line : lines) {
                Matcher matcher = Pattern.compile("^(\\s+)<").matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
            return "    ";
        }

        String text() {
            return String.join(newline, lines) + (trailingNewline ? newline : "");
        }
    }
}
