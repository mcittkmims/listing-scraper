package md.apartmentlens.scraper.source.okeyimobil;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.http.HttpStatusException;
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
 * OkeyImobil, an agency, read through the JSON API of its website. Its listing shows a summary of each ad,
 * 21 per request; the whole ad (with the description) is one more request, made only when the summary
 * changed.
 *
 * <p>The API returns the agency's internal notes: the owner's name and phone and the 999.md ad the property
 * came from. Only the 999.md link is kept.
 */
public class OkeyImobilSource implements ListingSource {

    private static final Logger log = LoggerFactory.getLogger(OkeyImobilSource.class);

    static final String API = "https://api.okeyimobil.md/api/property";
    static final List<String> TYPES = List.of("apartments", "houses");
    static final Pattern NINE_NINE_LINK = Pattern.compile("https?://(?:www\\.)?999\\.md/(?:ro/|ru/)?(\\d{6,10})");
    /** Fields that are not the ad: other properties shown beside it, counters, the old site's ids. */
    static final List<String> NOT_THE_AD = List.of("similar", "similar_recent", "views_count", "wp_attachments_json",
            "wp_post_id");

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final OkeyImobilParser parser = new OkeyImobilParser();

    public OkeyImobilSource(PoliteHttpClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String code() {
        return "okeyimobil";
    }

    @Override
    public int crawl(Consumer<Listed> sink) throws IOException {
        int advertised = 0;
        for (String type : TYPES) {
            int lastPage = 1;
            int total = -1;
            for (int page = 1; page <= lastPage; page++) {
                // Every filter on every page: the API's own next-page links drop them.
                JsonNode body = get(URI.create(API + "?deal=sale&property_type=" + type + "&locale=ro&sorting=newest&page="
                        + page));
                JsonNode items = body.path("data");
                if (!items.isArray()) {
                    throw new IOException("OkeyImobil returned no list of ads");
                }
                if (total < 0) {
                    total = body.path("total").asInt(-1);
                    lastPage = body.path("last_page").asInt(1) + 1; // one extra page for ads added during the pass
                }
                if (items.isEmpty()) {
                    break;
                }
                for (JsonNode item : items) {
                    sink.accept(Listed.summary(item.path("id").asString(), json.writeValueAsBytes(item)));
                }
            }
            advertised += Math.max(total, 0);
            log.info("OkeyImobil {}: {} ads advertised", type, total);
        }
        return advertised;
    }

    @Override
    public Optional<RawAd> fetch(String externalId) throws IOException {
        if (!externalId.matches("\\d+")) {
            throw new IllegalArgumentException("not an OkeyImobil id: " + externalId);
        }
        JsonNode ad;
        try {
            ad = get(URI.create(API + "/" + externalId + "?locale=ro"));
        } catch (HttpStatusException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        boolean listed = ad.path("published").asBoolean(false) && ad.path("deleted_at").isNull()
                && "sale".equals(ad.path("deal").asString()) && TYPES.contains(ad.path("property_type").asString());
        return listed ? Optional.of(toRaw(ad)) : Optional.empty();
    }

    @Override
    public SourceAd parse(RawAd raw) {
        return parser.parse(json.readTree(raw.body()));
    }

    @Override
    public byte[] stableContent(RawAd raw) {
        ObjectNode node = (ObjectNode) json.readTree(raw.body());
        node.remove(List.of("updated_at", "listed_at", "promo"));
        return json.writeValueAsBytes(node);
    }

    /** The ad with its internal notes reduced to the 999.md ads they link, and without other ads beside it. */
    RawAd toRaw(JsonNode ad) {
        ObjectNode clean = ((ObjectNode) ad).deepCopy();
        clean.remove(NOT_THE_AD);
        JsonNode agent = clean.path("agent");
        if (agent.isObject()) {
            ((ObjectNode) agent).remove(NOT_THE_AD);
        }
        ArrayNode links = json.createArrayNode();
        Matcher m = NINE_NINE_LINK.matcher(clean.path("notes").asString(""));
        List<String> seen = new ArrayList<>();
        while (m.find()) {
            if (!seen.contains(m.group(1))) {
                seen.add(m.group(1));
                links.add("https://999.md/ro/" + m.group(1));
            }
        }
        clean.remove("notes");
        clean.set("notes_999_links", links);
        return new RawAd(ad.path("id").asString(), json.writeValueAsBytes(clean));
    }

    private JsonNode get(URI uri) throws IOException {
        PoliteHttpClient.Response response = http.get(uri, Map.of("Accept", "application/json"));
        try {
            return json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new IOException("OkeyImobil returned a response that is not JSON", e);
        }
    }
}
