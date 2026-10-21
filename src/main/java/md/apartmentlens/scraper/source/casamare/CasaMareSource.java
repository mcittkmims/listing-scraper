package md.apartmentlens.scraper.source.casamare;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * CasaMare, a classifieds site for private sellers and agents, read through the JSON API of its website.
 * Its listing shows a summary of each ad (no Russian text, no contact), 100 per request; the whole ad is
 * one more request, made only when the summary changed, and at least every six days: its photo links are
 * signed for seven days, so the stored ones never expire.
 *
 * <p>The seller's e-mail address, which the API returns, is removed before anything is stored.
 */
public class CasaMareSource implements ListingSource {

    private static final Logger log = LoggerFactory.getLogger(CasaMareSource.class);

    static final String API = "https://casamare.md/casamare/public/objects";
    static final int PAGE_SIZE = 100; // the API's largest page
    static final List<String> KINDS = List.of("apartment", "house");

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final CasaMareParser parser = new CasaMareParser();

    public CasaMareSource(PoliteHttpClient http, JsonMapper json) {
        this.http = http;
        this.json = json;
    }

    @Override
    public String code() {
        return "casamare";
    }

    @Override
    public int crawl(Consumer<Listed> sink) throws IOException {
        int advertised = 0;
        for (String kind : KINDS) {
            int total = -1;
            int pages = 0;
            for (int page = 1; ; page++) {
                JsonNode body = get(URI.create(API + "?kind=" + kind + "&listingType=sale&sortBy=date&sortDir=desc&page="
                        + page + "&pageSize=" + PAGE_SIZE));
                JsonNode items = body.path("items");
                if (!items.isArray()) {
                    throw new IOException("CasaMare returned no list of ads");
                }
                if (total < 0) {
                    total = body.path("total").asInt(-1);
                    if (total < 0) {
                        throw new IOException("CasaMare sent no total");
                    }
                }
                for (JsonNode item : items) {
                    sink.accept(Listed.summary(item.path("displayId").asString(), summary(item)));
                }
                pages++;
                if (items.size() < PAGE_SIZE || page * PAGE_SIZE >= total + PAGE_SIZE) {
                    break; // the end, with one extra page for ads added during the pass
                }
            }
            advertised += total;
            log.info("CasaMare {}: {} ads advertised, {} pages read", kind, total, pages);
        }
        return advertised;
    }

    /** Under the seven days the photo links are signed for. */
    @Override
    public Duration detailMaxAge() {
        return Duration.ofDays(6);
    }

    @Override
    public Optional<RawAd> fetch(String externalId) throws IOException {
        if (!externalId.matches("\\d+")) {
            throw new IllegalArgumentException("not a CasaMare id: " + externalId);
        }
        JsonNode ad;
        try {
            ad = get(URI.create(API + "/by-id/" + externalId), AD_ATTEMPTS);
        } catch (HttpStatusException e) {
            if (e.status() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        boolean listed = "active".equals(ad.path("status").asString()) && "sale".equals(ad.path("listingType").asString())
                && KINDS.contains(ad.path("kind").asString()) && !ad.path("hidden").asBoolean(false);
        return listed ? Optional.of(toRaw(ad)) : Optional.empty();
    }

    @Override
    public SourceAd parse(RawAd raw) {
        return parser.parse(json.readTree(raw.body()));
    }

    @Override
    public byte[] stableContent(RawAd raw) {
        ObjectNode node = (ObjectNode) json.readTree(raw.body());
        node.remove(List.of("views", "updatedAt"));
        unsignPhotos(node);
        return json.writeValueAsBytes(node);
    }

    /** The listing's summary of an ad, without the photo links' signatures, which change every few days. */
    byte[] summary(JsonNode item) {
        ObjectNode node = ((ObjectNode) item).deepCopy();
        unsignPhotos(node);
        return json.writeValueAsBytes(node);
    }

    /** The ad without the seller's e-mail address. */
    RawAd toRaw(JsonNode ad) {
        ObjectNode clean = ((ObjectNode) ad).deepCopy();
        JsonNode contacts = clean.path("author").path("contacts");
        if (contacts.isObject()) {
            ((ObjectNode) contacts).remove("email");
        }
        return new RawAd(ad.path("displayId").asString(), json.writeValueAsBytes(clean));
    }

    private static void unsignPhotos(ObjectNode node) {
        for (JsonNode photo : node.path("photos")) {
            unsign(photo);
        }
        unsign(node.path("coverPhoto"));
    }

    private static void unsign(JsonNode photo) {
        if (photo instanceof ObjectNode o) {
            for (String field : List.of("webp", "fallback")) {
                String url = o.path(field).asString(null);
                if (url != null && url.indexOf('?') > 0) {
                    o.put(field, url.substring(0, url.indexOf('?')));
                }
            }
        }
    }

    private JsonNode get(URI uri) throws IOException {
        return get(uri, Integer.MAX_VALUE);
    }

    private JsonNode get(URI uri, int attempts) throws IOException {
        PoliteHttpClient.Response response = http.get(uri, Map.of("Accept", "application/json"), attempts);
        try {
            return json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new IOException("CasaMare returned a response that is not JSON", e);
        }
    }
}
