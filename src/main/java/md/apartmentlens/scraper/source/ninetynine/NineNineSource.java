package md.apartmentlens.scraper.source.ninetynine;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
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
 * 999.md, read through the GraphQL API its own website uses. One search request returns 78 ads with
 * every attribute, so a full pass over all flats and houses for sale takes a few hundred requests.
 */
public class NineNineSource implements ListingSource {

    private static final Logger log = LoggerFactory.getLogger(NineNineSource.class);

    static final URI ENDPOINT = URI.create("https://999.md/graphql");
    static final int PAGE_SIZE = 78; // the website's own page size
    static final int OFFER_SALE = 776;
    static final String DATE_FORMAT = "2006-01-02T15:04:05Z07:00";
    /** Real-estate subcategories collected: flats, houses, country houses ("vile"). */
    static final List<Integer> SUBCATEGORIES = List.of(1404, 1406, 6678);

    private static final String AD_FIELDS = "id title state subCategory { id } owner { id login } "
            + "posted(input: {format: \"" + DATE_FORMAT + "\"}) "
            + "reseted(input: {format: \"" + DATE_FORMAT + "\"}) "
            + NineNineFeatures.ALL.stream().map(id -> "f" + id + ": feature(id: " + id + ") { value }")
                    .collect(Collectors.joining(" "));
    static final String SEARCH_QUERY = "query Search($input: Ads_SearchInput!) { searchAds(input: $input) "
            + "{ count ads { " + AD_FIELDS + " } } }";
    static final String ADVERT_QUERY = "query Advert($id: String!) { advert(input: {id: $id}) { " + AD_FIELDS + " } }";

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final NineNineParser parser;
    private final int maxPagesPerCategory;

    public NineNineSource(PoliteHttpClient http, JsonMapper json, int maxPagesPerCategory) {
        this.http = http;
        this.json = json;
        this.parser = new NineNineParser();
        this.maxPagesPerCategory = maxPagesPerCategory;
    }

    @Override
    public String code() {
        return "999md";
    }

    @Override
    public int crawl(Consumer<Listed> sink) throws IOException {
        int advertised = 0;
        for (int subCategory : SUBCATEGORIES) {
            int skip = 0;
            int pages = 0;
            Integer count = null;
            while (true) {
                JsonNode result = graphql(SEARCH_QUERY, searchVariables(subCategory, skip)).path("searchAds");
                if (count == null) {
                    count = result.path("count").asInt(0);
                    advertised += count;
                }
                JsonNode ads = result.path("ads");
                if (!ads.isArray() || ads.isEmpty()) {
                    break;
                }
                for (JsonNode ad : ads) {
                    sink.accept(Listed.complete(toRaw(ad)));
                }
                pages++;
                skip += PAGE_SIZE;
                if (skip >= count + PAGE_SIZE || (maxPagesPerCategory > 0 && pages >= maxPagesPerCategory)) {
                    break; // the end, with one extra page for ads added during the pass
                }
            }
            log.info("999.md subcategory {}: {} ads advertised, {} pages read", subCategory, count, pages);
        }
        return advertised;
    }

    @Override
    public Optional<RawAd> fetch(String externalId) throws IOException {
        ObjectNode variables = json.createObjectNode().put("id", externalId);
        JsonNode ad = graphql(ADVERT_QUERY, variables).path("advert");
        if (ad.isMissingNode() || ad.isNull()) {
            return Optional.empty();
        }
        boolean listed = "AD_STATE_PUBLIC".equals(ad.path("state").asString(""))
                && SUBCATEGORIES.contains(ad.path("subCategory").path("id").asInt())
                && ad.path("f" + NineNineFeatures.OFFER_TYPE).path("value").path("value").asInt() == OFFER_SALE;
        return listed ? Optional.of(toRaw(ad)) : Optional.empty();
    }

    @Override
    public SourceAd parse(RawAd raw) {
        return parser.parse(json.readTree(raw.body()));
    }

    @Override
    public byte[] stableContent(RawAd raw) {
        ObjectNode node = (ObjectNode) json.readTree(raw.body());
        node.remove("reseted"); // changes every time the seller bumps the ad
        return json.writeValueAsBytes(node);
    }

    private RawAd toRaw(JsonNode ad) {
        return new RawAd(ad.path("id").asString(), json.writeValueAsBytes(ad));
    }

    private ObjectNode searchVariables(int subCategory, int skip) {
        ObjectNode input = json.createObjectNode();
        input.putObject("pagination").put("limit", PAGE_SIZE).put("skip", skip);
        input.put("sort", "SORT_ADS_DATE_DESC");
        input.put("source", "AD_SOURCE_DESKTOP_REDESIGN");
        input.put("subCategoryId", subCategory);
        ArrayNode filters = input.putArray("filters");
        ObjectNode offer = filters.addObject().put("filterId", 16);
        offer.putArray("features").addObject().put("featureId", NineNineFeatures.OFFER_TYPE)
                .putArray("optionIds").add(OFFER_SALE);
        ObjectNode variables = json.createObjectNode();
        variables.set("input", input);
        return variables;
    }

    private JsonNode graphql(String query, ObjectNode variables) throws IOException {
        ObjectNode request = json.createObjectNode().put("query", query);
        request.set("variables", variables);
        PoliteHttpClient.Response response = http.post(ENDPOINT, Map.of(
                "Content-Type", "application/json",
                "Accept", "application/json",
                "Origin", "https://999.md",
                "Referer", "https://999.md/ro",
                "lang", "ro"), json.writeValueAsBytes(request));
        JsonNode body;
        try {
            body = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new IOException("999.md returned a response that is not JSON", e);
        }
        JsonNode errors = body.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IOException("999.md GraphQL error: " + errors.get(0).path("message").asString(errors.toString()));
        }
        return body.path("data");
    }
}
