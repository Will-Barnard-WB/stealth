package dev.stealth.core.eol;

import dev.stealth.core.http.CachedHttpClient;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Looks up a product's release cycles and their support dates on endoflife.date. */
public class EndOfLifeClient {

    public static final URI ENDOFLIFE_DATE = URI.create("https://endoflife.date/api/");

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private static final Pattern PRODUCT = Pattern.compile("[a-z0-9]+([.-][a-z0-9]+)*");

    private final CachedHttpClient http;
    private final URI api;

    /**
     * @param api endoflife.date's API base URL, ending in {@code /}
     */
    public EndOfLifeClient(CachedHttpClient http, URI api) {
        this.http = Objects.requireNonNull(http, "http");
        this.api = Objects.requireNonNull(api, "api");
    }

    /**
     * Every release cycle of {@code product}, newest first as the API lists them, or empty if
     * endoflife.date doesn't track it.
     *
     * @param product an endoflife.date product slug, such as {@code spring-boot}
     * @throws IOException if endoflife.date can't be reached and nothing is cached
     */
    public Optional<List<ReleaseCycle>> cycles(String product)
            throws IOException, InterruptedException {
        if (!PRODUCT.matcher(product).matches()) {
            throw new IllegalArgumentException("not an endoflife.date product: " + product);
        }
        URI uri = api.resolve(product + ".json");
        Optional<String> body = http.get(uri);
        if (body.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(parse(body.get(), uri));
    }

    static List<ReleaseCycle> parse(String json, URI source) throws IOException {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IOException("Malformed response from " + source, e);
        }
        if (!root.isArray()) {
            throw new IOException("Expected an array of release cycles from " + source);
        }
        List<ReleaseCycle> cycles = new ArrayList<>();
        for (JsonNode node : root) {
            text(node, "cycle")
                    .ifPresent(
                            cycle ->
                                    cycles.add(
                                            new ReleaseCycle(
                                                    cycle,
                                                    support(node, "eol"),
                                                    support(node, "extendedSupport"),
                                                    text(node, "latest"))));
        }
        return List.copyOf(cycles);
    }

    private static Support support(JsonNode cycle, String field) {
        JsonNode value = cycle.get(field);
        if (value == null || value.isNull()) {
            return new Support.Open();
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? new Support.Ended() : new Support.Open();
        }
        try {
            return new Support.Until(LocalDate.parse(value.asString()));
        } catch (DateTimeParseException e) {
            return new Support.Open();
        }
    }

    private static Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.asString());
    }
}
