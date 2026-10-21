package md.apartmentlens.scraper.source.proimobil;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import md.apartmentlens.scraper.http.PoliteHttpClient;
import md.apartmentlens.scraper.source.Listed;
import md.apartmentlens.scraper.source.ListingSource;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proimobil, an agency, read through the JSON API of its website: one request returns up to 250 ads with
 * every attribute. Sold ads stay on the site marked "Vândut" and are not collected. The agency also lists
 * a few properties in Romania, which are left out.
 *
 * <p>The API returns the private owner's name and phone (marked {@code publish: false}) and agents' notes;
 * they are removed before anything is stored.
 */
public class ProimobilSource implements ListingSource {

    private static final Logger log = LoggerFactory.getLogger(ProimobilSource.class);

    static final String API = "https://api.proimobil.md/v1/properties";
    static final int PAGE_SIZE = 250; // the API's largest page
    static final List<String> CATEGORIES = List.of("apartment", "house");
    /** For sale and not sold ("deal" marks an ad sold). */
    static final String FOR_SALE = "status:active;offer:sell;deal:false";
    /**
     * The API's cities ({@code /v1/cities}) in Moldova: Chișinău, its suburbs ("Suburbie") and the districts.
     * 46-48 are Iași, Brașov and București; an id not listed here is left out and logged.
     */
    static final Set<Integer> MOLDOVA_CITIES = IntStream.rangeClosed(1, 45).filter(i -> i != 3).boxed()
            .collect(Collectors.toUnmodifiableSet());
    static final Set<Integer> FOREIGN_CITIES = Set.of(46, 47, 48);
    /** Agent fields kept: the rest are internal (notes, the agent's other properties, ratings). */
    static final Set<String> AGENT_FIELDS = Set.of("id", "surrogateId", "phone", "i18n");

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final ProimobilParser parser = new ProimobilParser();

    public ProimobilSource(PoliteHttpClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String code() {
        return "proimobil";
    }

    @Override
    public int crawl(Consumer<Listed> sink) throws IOException {
        int advertised = 0;
        for (String category : CATEGORIES) {
            int offset = 0;
            int total = -1;
            int abroad = 0;
            while (true) {
                PoliteHttpClient.Response response = http.get(search(FOR_SALE + ";category:" + category, offset,
                        PAGE_SIZE), Map.of("Accept", "application/json"));
                if (total < 0) {
                    total = response.header("X-Total-Count").map(String::strip).map(Integer::parseInt)
                            .orElseThrow(() -> new IOException("Proimobil sent no X-Total-Count"));
                }
                JsonNode page = read(response.body());
                for (JsonNode ad : page) {
                    if (inMoldova(ad)) {
                        sink.accept(Listed.complete(toRaw(ad)));
                    } else {
                        abroad++;
                    }
                }
                offset += PAGE_SIZE;
                if (page.size() < PAGE_SIZE || offset >= total + PAGE_SIZE) {
                    break; // the end, with one extra page for ads added during the pass
                }
            }
            advertised += total - abroad;
            log.info("Proimobil {}: {} ads advertised, {} outside Moldova left out", category, total, abroad);
        }
        return advertised;
    }

    /** By the property's id, the one filter the API applies exactly (it ignores one on surrogateId). */
    @Override
    public Optional<RawAd> fetch(String externalId) throws IOException {
        if (!externalId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("not a Proimobil id: " + externalId);
        }
        JsonNode page = read(http.get(search(FOR_SALE + ";id:" + externalId, 0, 1),
                Map.of("Accept", "application/json")).body());
        for (JsonNode ad : page) {
            if (ad.path("id").asString().equals(externalId) && CATEGORIES.contains(ad.path("category").asString())
                    && inMoldova(ad)) {
                return Optional.of(toRaw(ad));
            }
        }
        return Optional.empty();
    }

    @Override
    public SourceAd parse(RawAd raw) {
        return parser.parse(json.readTree(raw.body()));
    }

    @Override
    public byte[] stableContent(RawAd raw) {
        ObjectNode node = (ObjectNode) json.readTree(raw.body());
        // Counters, promotion and signed image links change without the ad changing.
        node.remove(List.of("views", "updatedAt", "isHot", "order", "assets"));
        return json.writeValueAsBytes(node);
    }

    boolean inMoldova(JsonNode ad) {
        int city = ad.path("_embedded").path("city").path("surrogateId").asInt(-1);
        if (MOLDOVA_CITIES.contains(city)) {
            return true;
        }
        if (!FOREIGN_CITIES.contains(city)) {
            log.warn("Proimobil ad {}: city {} ({}) is not known to be in Moldova; left out",
                    ad.path("surrogateId").asString(), city,
                    ad.path("_embedded").path("city").path("i18n").path("ro").path("name").asString(""));
        }
        return false;
    }

    /** The ad without the owner's private contact, agents' internal fields and the 999.md text. */
    RawAd toRaw(JsonNode ad) {
        ObjectNode clean = ((ObjectNode) ad).deepCopy();
        clean.remove("owner");
        JsonNode copy = clean.path("999");
        if (copy.isObject()) {
            ((ObjectNode) copy).retain("id", "title");
        }
        JsonNode agents = clean.path("_embedded").path("agents");
        if (agents.isArray()) {
            ArrayNode kept = json.createArrayNode();
            for (JsonNode agent : agents) {
                kept.add(((ObjectNode) agent.deepCopy()).retain(AGENT_FIELDS));
            }
            ((ObjectNode) clean.path("_embedded")).set("agents", kept);
        }
        return new RawAd(ad.path("id").asString(), json.writeValueAsBytes(clean));
    }

    private JsonNode read(byte[] body) throws IOException {
        JsonNode page;
        try {
            page = json.readTree(body);
        } catch (RuntimeException e) {
            throw new IOException("Proimobil returned a response that is not JSON", e);
        }
        if (!page.isArray()) {
            throw new IOException("Proimobil returned " + page.getNodeType() + " instead of a list of ads");
        }
        return page;
    }

    private static URI search(String filter, int offset, int limit) {
        return URI.create(API + "?filter=" + URLEncoder.encode(filter, StandardCharsets.UTF_8)
                + "&sort=-surrogateId&limit=" + limit + "&offset=" + offset + "&embedded=agents,city,region");
    }
}
