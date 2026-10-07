package dev.stealth.core.hook;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The dependencies a POM declares with a version, read from its text alone (fast enough for a hook
 * that runs after every edit): {@code <dependencies>} and {@code <dependencyManagement>}, with
 * {@code ${property}} versions filled in from the same POM's {@code <properties>}. Versions that
 * come from elsewhere (a parent, a BOM) aren't known here and are left out.
 */
final class PomDependencies {

    private static final Pattern PROPERTY = Pattern.compile("\\$\\{([^}]+)}");

    private PomDependencies() {}

    /** {@code groupId:artifactId} → version; empty if the text isn't a readable POM. */
    static Map<String, String> of(String pom) {
        List<String[]> declared = new ArrayList<>();
        Map<String, String> properties = new HashMap<>();
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader xml = factory.createXMLStreamReader(new StringReader(pom));
            List<String> path = new ArrayList<>();
            String[] dependency = null;
            StringBuilder text = new StringBuilder();
            while (xml.hasNext()) {
                int event = xml.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    path.add(xml.getLocalName());
                    text.setLength(0);
                    String at = String.join("/", path);
                    if (at.equals("project/dependencies/dependency")
                            || at.equals("project/dependencyManagement/dependencies/dependency")) {
                        dependency = new String[3];
                    }
                } else if (event == XMLStreamConstants.CHARACTERS) {
                    text.append(xml.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String at = String.join("/", path);
                    String value = text.toString().strip();
                    if (path.size() == 3 && at.startsWith("project/properties/")) {
                        properties.put(xml.getLocalName(), value);
                    } else if (dependency != null && at.endsWith("dependency/groupId")) {
                        dependency[0] = value;
                    } else if (dependency != null && at.endsWith("dependency/artifactId")) {
                        dependency[1] = value;
                    } else if (dependency != null && at.endsWith("dependency/version")) {
                        dependency[2] = value;
                    } else if (dependency != null && at.endsWith("/dependency")) {
                        declared.add(dependency);
                        dependency = null;
                    }
                    path.removeLast();
                    text.setLength(0);
                }
            }
        } catch (XMLStreamException e) {
            // Mid-edit or broken: nothing to check
            return Map.of();
        }
        Map<String, String> versions = new LinkedHashMap<>();
        for (String[] d : declared) {
            if (d[0] == null || d[1] == null || d[2] == null) {
                continue;
            }
            String version = resolve(d[2], properties);
            if (version != null) {
                versions.putIfAbsent(d[0] + ":" + d[1], version);
            }
        }
        return versions;
    }

    /** The version with this POM's properties filled in, or null if one isn't defined here. */
    private static String resolve(String version, Map<String, String> properties) {
        String resolved = version;
        for (int depth = 0; depth < 5; depth++) {
            Matcher matcher = PROPERTY.matcher(resolved);
            if (!matcher.find()) {
                return resolved;
            }
            String value = properties.get(matcher.group(1));
            if (value == null) {
                return null;
            }
            resolved = matcher.replaceFirst(Matcher.quoteReplacement(value));
        }
        return null;
    }
}
