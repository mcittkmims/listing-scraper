package md.apartmentlens.scraper.source.romanescu;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.http.HttpStatusException;
import md.apartmentlens.scraper.http.PoliteHttpClient;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.source.Listed;
import md.apartmentlens.scraper.source.ListingSource;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Romanescu, an agency whose website serves HTML pages. A listing page shows 12 ads as cards (title, place,
 * price, rooms, floor), which are the summaries; the ad page is read when the card changed.
 *
 * <p>An ad page is stored as an extract of what describes the property (the offer's structured data, the
 * breadcrumbs, the table of features, the map point and the phone links), not as the whole page. The site's
 * structured data is malformed (trailing commas, missing closing braces) and is repaired before reading.
 *
 * <p>The agency also sells properties in Romania (Brașov), named like Moldovan places but with no country: an
 * ad whose place is not a settlement of Moldova is left out and logged.
 */
public class RomanescuSource implements ListingSource {

    private static final Logger log = LoggerFactory.getLogger(RomanescuSource.class);

    static final String SITE = "https://romanescu.md";
    static final List<String> CATEGORIES = List.of("imobiliare-apartamente-de-vanzare", "imobiliare-case-de-vanzare");
    /** The breadcrumbs of an ad for sale in a collected category: "Vanzare", then "Apartamente" or "Case". */
    static final Set<String> KINDS = Set.of("Apartamente", "Case");
    static final Pattern ID = Pattern.compile("-(\\d+)$");
    static final int MAX_PAGES = 200;
    private static final Pattern TRAILING_COMMA = Pattern.compile(",(\\s*[}\\]])");
    private static final Pattern NAME = Pattern.compile("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final PlaceGazetteer places;
    private final RomanescuParser parser = new RomanescuParser();

    public RomanescuSource(PoliteHttpClient http, JsonMapper json, PlaceGazetteer places) {
        this.http = http;
        this.json = json;
        this.places = places;
    }

    @Override
    public String code() {
        return "romanescu";
    }

    @Override
    public int crawl(Consumer<Listed> sink) throws IOException {
        for (String category : CATEGORIES) {
            Set<String> seen = new HashSet<>();
            Set<String> abroad = new TreeSet<>();
            int pages = 0;
            for (int page = 1; page <= MAX_PAGES; page++) {
                Document doc = page(URI.create(SITE + "/" + category + "?page=" + page));
                Map<String, String> cards = cards(doc);
                Map<String, String> placesOfCards = cardPlaces(doc);
                if (cards.isEmpty() && page == 1) {
                    throw new IOException("Romanescu listing page without ads: " + category);
                }
                // Past the last page the site shows no cards, or the first page again.
                int before = seen.size();
                seen.addAll(cards.keySet());
                if (cards.isEmpty() || seen.size() == before) {
                    break;
                }
                pages++;
                for (Map.Entry<String, String> card : cards.entrySet()) {
                    String place = placesOfCards.get(card.getKey());
                    if (place != null && !inMoldova(place)) {
                        abroad.add(place);
                        continue;
                    }
                    Matcher m = ID.matcher(card.getKey());
                    if (!m.find()) {
                        throw new IOException("Romanescu listed an ad without an id: " + card.getKey());
                    }
                    sink.accept(Listed.summary(m.group(1), card.getValue().getBytes(StandardCharsets.UTF_8)));
                }
            }
            log.info("Romanescu {}: {} ads on {} pages", category, seen.size(), pages);
            if (!abroad.isEmpty()) {
                log.warn("Romanescu {}: ads in places that are not settlements of Moldova left out: {}", category, abroad);
            }
        }
        return -1; // the site states no total
    }

    @Override
    public Optional<RawAd> fetch(String externalId) throws IOException {
        if (!externalId.matches("\\d+")) {
            throw new IllegalArgumentException("not a Romanescu id: " + externalId);
        }
        Document doc;
        try {
            doc = page(URI.create(SITE + "/" + externalId), AD_ATTEMPTS); // redirects to the ad's page
        } catch (HttpStatusException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        ObjectNode extract = extract(doc);
        JsonNode crumbs = extract.path("breadcrumbs");
        boolean listed = extract.path("offer").path("url").asString("").endsWith("-" + externalId)
                && extract.path("offer").path("availability").asString("").endsWith("InStock")
                && crumbs.size() >= 3 && "Vanzare".equals(crumbs.get(0).asString())
                && KINDS.contains(crumbs.get(1).asString()) && inMoldova(crumbs.get(2).asString());
        return listed ? Optional.of(new RawAd(externalId, json.writeValueAsBytes(extract))) : Optional.empty();
    }

    @Override
    public SourceAd parse(RawAd raw) {
        return parser.parse(json.readTree(raw.body()));
    }

    @Override
    public byte[] stableContent(RawAd raw) {
        return raw.body();
    }

    /** Each ad's card text on a listing page, by the ad's address. */
    static Map<String, String> cards(Document doc) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element link : doc.select(".property-card a.stretched-link[href]")) {
            Element card = link.closest(".property-card");
            out.putIfAbsent(link.absUrl("href"), card == null ? link.attr("title") : card.text());
        }
        return out;
    }

    /** Each ad's place on a listing page ("Chisinau, Botanica": the settlement), by the ad's address. */
    static Map<String, String> cardPlaces(Document doc) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Element link : doc.select(".property-card a.stretched-link[href]")) {
            Element card = link.closest(".property-card");
            Element location = card == null ? null : card.selectFirst(".location");
            if (location != null && !location.text().isBlank()) {
                out.putIfAbsent(link.absUrl("href"), location.text().split(",")[0].strip());
            }
        }
        return out;
    }

    boolean inMoldova(String settlement) {
        return places.settlement(settlement, null, null).isPresent();
    }

    /** What an ad page says about the property. */
    ObjectNode extract(Document doc) {
        ObjectNode out = json.createObjectNode();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            String text = script.data();
            if (text.contains("\"Offer\"")) {
                JsonNode offer = lenient(text);
                if (offer != null) {
                    out.set("offer", offer);
                }
            } else if (text.contains("BreadcrumbList")) {
                // Its keys repeat ("itemListElement" once per crumb), so only the names are read, in order.
                ArrayNode crumbs = out.putArray("breadcrumbs");
                Matcher m = NAME.matcher(text);
                while (m.find()) {
                    crumbs.add(json.readTree("\"" + m.group(1) + "\"").asString());
                }
            } else if (text.contains("RealEstateAgent")) {
                JsonNode agency = lenient(text);
                if (agency != null) {
                    out.put("agencyPhone", agency.path("telephone").asString(null));
                }
            }
        }
        Element h1 = doc.selectFirst("h1");
        out.put("title", h1 == null ? null : h1.text());
        ObjectNode features = out.putObject("features");
        for (Element item : doc.select(".property-show-caracteristics li")) {
            Element value = item.selectFirst("span");
            String label = item.ownText().strip().replaceFirst(":$", "");
            if (value != null && !label.isEmpty()) {
                features.put(label, value.text());
            }
        }
        ArrayNode specs = out.putArray("specs");
        doc.select(".property-show-specs .spec-item").forEach(s -> specs.add(s.text()));
        Element map = doc.selectFirst(".map[data-lat]");
        if (map != null) {
            out.putObject("map").put("lat", map.attr("data-lat")).put("lng", map.attr("data-lng"))
                    .put("exact", map.attr("data-exact")).put("range", map.attr("data-range"));
        }
        ArrayNode phones = out.putArray("phones");
        doc.select("a[href^=tel:]").stream().map(a -> a.attr("href").substring(4).strip()).distinct()
                .forEach(phones::add);
        return out;
    }

    /** Structured data with the site's mistakes repaired: trailing commas, closing braces left out. */
    JsonNode lenient(String text) {
        String s = TRAILING_COMMA.matcher(text.strip()).replaceAll("$1");
        for (int missing = 0; missing <= 3; missing++) {
            try {
                return json.readTree(s + "}".repeat(missing));
            } catch (RuntimeException e) {
                // try with one more closing brace
            }
        }
        return null;
    }

    private Document page(URI uri) throws IOException {
        return page(uri, Integer.MAX_VALUE);
    }

    private Document page(URI uri, int attempts) throws IOException {
        PoliteHttpClient.Response response = http.get(uri, Map.of("Accept", "text/html"), attempts);
        return Jsoup.parse(new String(response.body(), StandardCharsets.UTF_8), uri.toString());
    }
}
