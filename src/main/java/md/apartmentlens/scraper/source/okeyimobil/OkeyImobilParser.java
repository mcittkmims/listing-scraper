package md.apartmentlens.scraper.source.okeyimobil;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import md.apartmentlens.scraper.source.Coded;
import md.apartmentlens.scraper.source.Quantity;
import md.apartmentlens.scraper.source.RawPrice;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.SourceCategory;
import md.apartmentlens.scraper.text.Html;
import md.apartmentlens.scraper.text.Numbers;
import md.apartmentlens.scraper.text.Text;
import tools.jackson.databind.JsonNode;

/**
 * Reads one OkeyImobil property into a {@link SourceAd}. The API's codes are turned into the labels the
 * website shows, which the normalizer knows; an unknown code is passed on as it is and reported there.
 */
final class OkeyImobilParser {

    /** The API's region of Chișinău (its locations are sectors) and of the suburbs (settlements). */
    static final int CHISINAU = 22;
    static final int SUBURBS = 28;
    /** The location "alte" (other towns): the town is then written as the address. */
    static final String OTHER = "alte";

    static final Map<String, String> CONDITION = Map.of(
            "euro_renovation", "Euroreparație",
            "white_variant", "Variantă albă",
            "gray_variant", "Variantă sură",
            "cosmetic_repair", "Reparație cosmetică",
            "livable_condition", "Stare locativă");
    static final Map<String, String> HOUSING_STOCK = Map.of("new", "Construcție nouă", "old", "Secundar");
    /** The website's option names of the property itself (the surroundings are not amenities), folded. */
    static final Map<String, String> AMENITIES = folded(Map.ofEntries(
            Map.entry("Geamuri termopan", "double_glazing"),
            Map.entry("Încălzire autonomă", "autonomous_heating"),
            Map.entry("Ușă blindată", "armored_door"),
            Map.entry("Teren de joacă", "playground"),
            Map.entry("Cablu TV", "cable_tv"),
            Map.entry("Linie telefonică", "phone_line"),
            Map.entry("Internet", "internet"),
            Map.entry("Ascensor", "elevator"),
            Map.entry("Interfon", "intercom"),
            Map.entry("Mobilat", "furnished"),
            Map.entry("Aer condiționat", "air_conditioning"),
            Map.entry("Garaj", "garage"),
            Map.entry("Parchet", "parquet"),
            Map.entry("Laminat", "laminate"),
            Map.entry("Saună", "sauna"),
            Map.entry("Piscină", "pool"),
            Map.entry("Terasă", "terrace"),
            Map.entry("Beci", "cellar"),
            Map.entry("Supraveghere video", "video_surveillance")));

    SourceAd parse(JsonNode ad) {
        String type = text(ad.path("property_type"));
        JsonNode location = ad.path("location");
        String place = text(location.path("name"));
        int region = location.path("region_id").asInt(-1);
        String address = text(ad.path("address"));
        boolean other = OTHER.equals(place);
        String locality = region == CHISINAU ? "Chișinău" : other ? address : place;
        String district = region == CHISINAU && !"Chișinău".equals(place) ? place : null;

        JsonNode agent = ad.path("agent");
        String phone = text(agent.path("phone"));
        Set<String> amenities = amenities(ad.path("options"));
        if ("autonomous".equals(text(ad.path("heating_type")))) {
            amenities.add("autonomous_heating");
        }
        List<String> crosslinks = new ArrayList<>();
        for (JsonNode link : ad.path("notes_999_links")) {
            String url = text(link);
            crosslinks.add("999md:" + url.substring(url.lastIndexOf('/') + 1));
        }

        return SourceAd.builder()
                .externalId(ad.path("id").asString())
                .url("https://okeyimobil.md/properties/" + ad.path("id").asString())
                .category(category(type))
                .offerType("sale".equals(text(ad.path("deal"))) ? "Vânzare" : text(ad.path("deal")))
                .sellerType("Agenție")
                .sellerName("OkeyImobil")
                .sellerAccount(text(agent.path("id")))
                .title(text(ad.path("title")))
                .descriptionRo(Html.toText(text(ad.path("description"))))
                // The website shows prices in euros; the API gives no currency.
                .price(ad.path("price").isNumber() || text(ad.path("price")) != null
                        ? new RawPrice(Numbers.parseDecimal(text(ad.path("price"))).orElse(null), "EUR", null,
                        ad.path("negotiable").asBoolean(false) ? Boolean.TRUE : null, null) : null)
                .rooms(text(ad.path("rooms")))
                .areaTotal(number(ad.path("total_area"), "m2"))
                .landArea(number(ad.path("ari"), "ar"))
                .floor(text(ad.path("floor")))
                .floorsTotal(text(ad.path("total_floors")))
                .housingStock(label(HOUSING_STOCK, ad.path("construction")))
                .condition(label(CONDITION, ad.path("state")))
                .bathrooms(text(ad.path("bathrooms")))
                .region(region == CHISINAU || region == SUBURBS ? new Coded(null, "Chișinău") : null)
                .locality(locality == null ? null : new Coded(null, locality))
                .district(district == null ? null : new Coded(null, district))
                .street(other ? null : address)
                .phones(phone == null ? List.of() : List.of(phone))
                .photoKeys(photos(ad))
                .hasVideo(ad.path("videos").isArray() && !ad.path("videos").isEmpty())
                .amenities(amenities)
                .crosslinks(crosslinks)
                .postedAt(instant(ad.path("created_at")))
                .build();
    }

    static SourceCategory category(String type) {
        return switch (type) {
            case "apartments" -> SourceCategory.APARTMENTS;
            case "houses" -> SourceCategory.HOUSES;
            default -> throw new IllegalArgumentException("OkeyImobil type " + type + " is not collected");
        };
    }

    /** "65.00"; "0.00" means not filled in. */
    private static Quantity number(JsonNode n, String unit) {
        return Numbers.parseDecimal(text(n)).filter(v -> v.signum() > 0).map(v -> Quantity.of(v, unit)).orElse(null);
    }

    private static String label(Map<String, String> labels, JsonNode code) {
        String c = text(code);
        return c == null ? null : labels.getOrDefault(c, c);
    }

    private static List<String> photos(JsonNode ad) {
        List<String> out = new ArrayList<>();
        for (JsonNode url : ad.path("gallery").path("original")) {
            String u = text(url);
            if (u != null && !out.contains(u)) {
                out.add(u);
            }
        }
        if (out.isEmpty() && text(ad.path("thumbnail").path("original")) != null) {
            out.add(text(ad.path("thumbnail").path("original")));
        }
        return out;
    }

    private static Set<String> amenities(JsonNode options) {
        Set<String> out = new TreeSet<>();
        for (JsonNode o : options) {
            String code = AMENITIES.get(Text.fold(text(o.path("name"))));
            if (code != null) {
                out.add(code);
            }
        }
        return out;
    }

    private static Map<String, String> folded(Map<String, String> byLabel) {
        Map<String, String> out = new HashMap<>();
        byLabel.forEach((label, code) -> out.put(Text.fold(label), code));
        return Map.copyOf(out);
    }

    private static Instant instant(JsonNode n) {
        String s = text(n);
        return s == null ? null : Instant.parse(s);
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = n.isString() ? n.stringValue() : n.asString();
        return s == null || s.isBlank() ? null : s.strip();
    }
}
