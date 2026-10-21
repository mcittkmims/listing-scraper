package md.apartmentlens.scraper.source.realist;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.http.HttpStatusException;
import md.apartmentlens.scraper.http.PoliteHttpClient;
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
 * Realist, an agency whose website serves HTML pages. A listing page names 15 ads (structured data) with a
 * card each (title, price, area), which is the summary; the ad page is read when the card changed.
 *
 * <p>An ad page is stored as an extract of what describes the property (its structured data, the table of
 * features, title, breadcrumbs, map point and the agent's phone), not as the whole page, whose menus and
 * suggestions change all the time.
 */
public class RealistSource implements ListingSource {

    private static final Logger log = LoggerFactory.getLogger(RealistSource.class);

    static final String SITE = "https://www.realist.md";
    static final List<String> CATEGORIES = List.of("apartamente-de-vanzare", "case-vile-de-vanzare");
    static final Pattern ID = Pattern.compile("-cp(\\d+)/?$");
    /** A stop for a listing that does not end: more pages than the site could have. */
    static final int MAX_PAGES = 200;

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final RealistParser parser = new RealistParser();

    public RealistSource(PoliteHttpClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String code() {
        return "realist";
    }

    @Override
    public int crawl(Consumer<Listed> sink) throws IOException {
        for (String category : CATEGORIES) {
            Set<String> seen = new HashSet<>();
            int pages = 0;
            for (int page = 1; page <= MAX_PAGES; page++) {
                Document doc = page(URI.create(SITE + "/" + category + "/?page=" + page));
                Map<String, String> cards = cards(doc);
                List<String> urls = listed(doc);
                // Past the last page the site sends the first one again.
                int before = seen.size();
                seen.addAll(urls);
                if (urls.isEmpty() || seen.size() == before) {
                    break;
                }
                pages++;
                for (String url : urls) {
                    Matcher m = ID.matcher(url);
                    if (!m.find()) {
                        throw new IOException("Realist listed an ad without an id: " + url);
                    }
                    String card = cards.getOrDefault(url, url);
                    sink.accept(Listed.summary(m.group(1), card.getBytes(StandardCharsets.UTF_8)));
                }
            }
            log.info("Realist {}: {} ads on {} pages", category, seen.size(), pages);
        }
        return -1; // the site states no total
    }

    @Override
    public Optional<RawAd> fetch(String externalId) throws IOException {
        if (!externalId.matches("\\d+")) {
            throw new IllegalArgumentException("not a Realist id: " + externalId);
        }
        Document doc;
        try {
            doc = page(URI.create(SITE + "/cp" + externalId + "/"), AD_ATTEMPTS);
        } catch (HttpStatusException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        ObjectNode extract = extract(doc);
        JsonNode listing = extract.path("listing");
        boolean listed = listing.path("url").asString("").endsWith("-cp" + externalId + "/")
                && listing.path("offers").path("businessFunction").asString("").endsWith("#Sell")
                && listing.path("offers").path("availability").asString("").endsWith("InStock")
                && RealistParser.category(listing) != null;
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

    /** The ads a listing page names, from its structured data (other links on the page are promotions). */
    List<String> listed(Document doc) throws IOException {
        for (JsonNode ld : structuredData(doc)) {
            if ("ItemList".equals(ld.path("@type").asString())) {
                return ld.path("itemListElement").valueStream().map(e -> e.path("url").asString()).toList();
            }
        }
        throw new IOException("Realist listing page without its list of ads");
    }

    /** Each ad's card text on a listing page, by the ad's address. */
    static Map<String, String> cards(Document doc) {
        Map<String, String> out = new HashMap<>();
        for (Element card : doc.select(".row-property")) {
            Element link = card.selectFirst("a[href*=-cp]");
            if (link != null) {
                out.putIfAbsent(link.absUrl("href").replaceFirst("#.*$", ""), card.text());
            }
        }
        return out;
    }

    /** What an ad page says about the property. */
    ObjectNode extract(Document doc) {
        ObjectNode out = json.createObjectNode();
        for (JsonNode ld : structuredData(doc)) {
            JsonNode type = ld.path("@type");
            boolean listing = type.isArray() ? type.valueStream().anyMatch(t -> t.asString().equals("RealEstateListing"))
                    : type.asString().equals("RealEstateListing");
            if (listing) {
                out.set("listing", ld);
            } else if (type.asString().equals("BreadcrumbList")) {
                ArrayNode crumbs = out.putArray("breadcrumbs");
                ld.path("itemListElement").forEach(e -> crumbs.add(e.path("name").asString()));
            }
        }
        Element h1 = doc.selectFirst("h1");
        out.put("title", h1 == null ? null : h1.text());
        ObjectNode features = out.putObject("features");
        for (Element label : doc.select(".property-features-label")) {
            Element value = label.nextElementSibling();
            if (value != null) {
                features.put(label.text(), value.text());
            }
        }
        Element map = doc.selectFirst(".map[data-lat]");
        if (map != null) {
            out.putObject("map").put("lat", map.attr("data-lat")).put("lng", map.attr("data-lng"))
                    .put("fuzzy", map.attr("data-fuzzy"));
        }
        ArrayNode phones = out.putArray("phones"); // the agent's; the office's are elsewhere on the page
        doc.select(".agent-phone a[href^=tel:], a.agent-phone[href^=tel:]").stream()
                .map(a -> a.attr("href").substring(4).strip()).distinct().forEach(phones::add);
        return out;
    }

    private List<JsonNode> structuredData(Document doc) {
        return doc.select("script[type=application/ld+json]").stream().map(s -> {
            try {
                return json.readTree(s.data());
            } catch (RuntimeException e) {
                return null;
            }
        }).filter(n -> n != null && n.isObject()).toList();
    }

    private Document page(URI uri) throws IOException {
        return page(uri, Integer.MAX_VALUE);
    }

    private Document page(URI uri, int attempts) throws IOException {
        PoliteHttpClient.Response response = http.get(uri, Map.of("Accept", "text/html"), attempts);
        return Jsoup.parse(new String(response.body(), StandardCharsets.UTF_8), uri.toString());
    }
}
