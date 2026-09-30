package dev.stealth.core.deps;

import dev.stealth.core.http.CachedHttpClient;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Looks up the published versions of an artifact from its {@code maven-metadata.xml} on Maven
 * Central, which is more reliable than the search API and has no search rate limits.
 */
public class MavenCentralClient {

    private final CachedHttpClient http;
    private final URI repository;

    /**
     * @param repository Maven Central's base URL, or a mirror's, ending in {@code /}
     */
    public MavenCentralClient(CachedHttpClient http, URI repository) {
        this.http = Objects.requireNonNull(http, "http");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Every version of {@code groupId:artifactId}, oldest first as the metadata lists them, or
     * empty if the artifact isn't in the repository (for example a company's internal library).
     *
     * @throws IOException if the repository can't be reached and nothing is cached
     */
    public Optional<List<String>> versions(String groupId, String artifactId)
            throws IOException, InterruptedException {
        URI metadata =
                repository.resolve(
                        groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml");
        Optional<String> body = http.get(metadata);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parseVersions(body.get(), metadata));
    }

    static List<String> parseVersions(String xml, URI source) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Untrusted input: no DTDs, no external entities
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            Document document =
                    factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            NodeList nodes = document.getElementsByTagName("version");
            List<String> versions = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                // <version> also appears at the top level of some metadata; only take listed ones
                if ("versions".equals(nodes.item(i).getParentNode().getNodeName())) {
                    versions.add(nodes.item(i).getTextContent().trim());
                }
            }
            return versions;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Malformed maven-metadata.xml from " + source, e);
        }
    }
}
