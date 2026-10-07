package dev.stealth.core.impact;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Which source lines the tests executed, from each module's JaCoCo XML report ({@code
 * target/site/jacoco/jacoco.xml}).
 *
 * @param lines every line with code, by repo-relative source file
 * @param covered the lines some test executed
 */
record Coverage(Map<String, Set<Integer>> lines, Map<String, Set<Integer>> covered) {

    static final Path REPORT = Path.of("target", "site", "jacoco", "jacoco.xml");

    boolean isEmpty() {
        return lines.isEmpty();
    }

    boolean covers(String file, int line) {
        return covered.getOrDefault(file, Set.of()).contains(line);
    }

    static Coverage read(Path root) throws IOException {
        Map<String, Set<Integer>> lines = new HashMap<>();
        Map<String, Set<Integer>> covered = new HashMap<>();
        List<Path> reports;
        try (Stream<Path> files = Files.walk(root)) {
            reports = files.filter(f -> f.endsWith(REPORT)).toList();
        }
        for (Path report : reports) {
            Path module = report.getParent().getParent().getParent().getParent();
            Path sources = module.resolve("src/main/java");
            read(report, root, sources, lines, covered);
        }
        return new Coverage(lines, covered);
    }

    static void read(
            Path report,
            Path root,
            Path sources,
            Map<String, Set<Integer>> lines,
            Map<String, Set<Integer>> covered)
            throws IOException {
        try (InputStream in = Files.newInputStream(report)) {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            // jacoco.xml declares a DTD it doesn't need
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader xml = factory.createXMLStreamReader(in);
            String packageName = "";
            String file = null;
            while (xml.hasNext()) {
                if (xml.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                switch (xml.getLocalName()) {
                    case "package" -> packageName = xml.getAttributeValue(null, "name");
                    case "sourcefile" -> {
                        String name = xml.getAttributeValue(null, "name");
                        Path source =
                                sources.resolve(
                                        packageName.isEmpty() ? name : packageName + "/" + name);
                        file = root.relativize(source).toString().replace('\\', '/');
                    }
                    case "line" -> {
                        if (file != null) {
                            int number = Integer.parseInt(xml.getAttributeValue(null, "nr"));
                            int coveredInstructions =
                                    Integer.parseInt(xml.getAttributeValue(null, "ci"));
                            lines.computeIfAbsent(file, f -> new HashSet<>()).add(number);
                            if (coveredInstructions > 0) {
                                covered.computeIfAbsent(file, f -> new HashSet<>()).add(number);
                            }
                        }
                    }
                    default -> {}
                }
            }
        } catch (XMLStreamException e) {
            throw new IOException("can't read " + report + ": " + e.getMessage(), e);
        }
    }
}
