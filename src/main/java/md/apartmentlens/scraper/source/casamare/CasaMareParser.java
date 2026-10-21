package md.apartmentlens.scraper.source.casamare;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import md.apartmentlens.scraper.source.Coded;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.source.Quantity;
import md.apartmentlens.scraper.source.RawPrice;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.SourceCategory;
import md.apartmentlens.scraper.text.Html;
import md.apartmentlens.scraper.text.Text;
import tools.jackson.databind.JsonNode;

/**
 * Reads one CasaMare ad into a {@link SourceAd}. The API's codes are turned into the labels the website
 * shows, which the normalizer knows; an unknown code is passed on as it is and reported there.
 */
final class CasaMareParser {

    static final Map<String, String> SELLER = Map.of("owner", "Proprietar", "agent", "Agenție", "agency", "Agenție",
            "developer", "Dezvoltator");
    static final Map<String, String> CONDITION = Map.of(
            "euroreparatie", "Euroreparație",
            "varianta-alba", "Variantă albă",
            "varianta-sura", "Variantă sură",
            "cosmetica", "Reparație cosmetică",
            "fara-reparatie", "Fără reparație",
            "cladire-nefinisata", "Construcție nefinisată");
    static final Map<String, String> HOUSING_STOCK = Map.of("bloc-nou", "Construcții noi", "bloc-secundar", "Secundar");
    static final Map<String, String> SERIES = Map.ofEntries(
            Map.entry("seria-143", "143"),
            Map.entry("seria-135", "135"),
            Map.entry("seria-102", "102"),
            Map.entry("seria-ms", "Ms"),
            Map.entry("seria-varnita", "Varnițkaia"),
            Map.entry("seria-ceha", "Ceșka"),
            Map.entry("hrusciovka", "Hrușciovka"),
            Map.entry("brejnevka", "Brejnevka"),
            Map.entry("stalinka", "Stalinka"),
            Map.entry("rubaska", "Rubașka"),
            Map.entry("improvizat", "Îmbunătățită"));
    /** The website's feature names of the property itself (the surroundings are not amenities), folded. */
    static final Map<String, String> AMENITIES = folded(Map.ofEntries(
            Map.entry("Geamuri termopan", "double_glazing"),
            Map.entry("Ușă blindată", "armored_door"),
            Map.entry("Încălzire autonomă", "autonomous_heating"),
            Map.entry("Interfon", "intercom"),
            Map.entry("Cu electrocasnice", "appliances"),
            Map.entry("Mobilat", "furnished"),
            Map.entry("Ascensor", "elevator"),
            Map.entry("Gata de mutat", "move_in_ready"),
            Map.entry("Teren de joacă", "playground"),
            Map.entry("Aparat de aer condiționat", "air_conditioning"),
            Map.entry("Geamuri panoramice", "panoramic_windows"),
            Map.entry("Pardosea caldă", "floor_heating"),
            Map.entry("Supraveghere video", "video_surveillance"),
            Map.entry("Laminat", "laminate"),
            Map.entry("Sistem de alarmă", "alarm"),
            Map.entry("Terasă", "terrace"),
            Map.entry("Parchet", "parquet"),
            Map.entry("Canalizare", "sewerage"),
            Map.entry("Apă", "plumbing"),
            Map.entry("Gaz", "gas"),
            Map.entry("Beci", "cellar"),
            Map.entry("Garaj", "garage"),
            Map.entry("Intrare separată", "separate_entrance"),
            Map.entry("Sistem „Casă inteligentă\"", "smart_home"),
            Map.entry("Poartă cu telecomandă", "remote_gate"),
            Map.entry("Anexă", "annex"),
            Map.entry("Saună", "sauna"),
            Map.entry("Șemineu", "fireplace"),
            Map.entry("Bazin", "pool")));

    SourceAd parse(JsonNode ad) {
        String kind = text(ad.path("kind"));
        JsonNode location = ad.path("location");
        JsonNode author = ad.path("author");
        JsonNode geo = location.path("geo");
        String city = text(location.path("city"));
        String district = text(location.path("district"));
        String phone = text(author.path("contacts").path("phone"));

        return SourceAd.builder()
                .externalId(ad.path("displayId").asString())
                .url("https://casamare.md/" + text(ad.path("slug")) + "/")
                .category(category(kind))
                .offerType("sale".equals(text(ad.path("listingType"))) ? "Vânzare" : text(ad.path("listingType")))
                .sellerType(label(SELLER, author.path("kind")))
                .sellerAccount(text(ad.path("agentId")) != null ? text(ad.path("agentId")) : text(author.path("id")))
                .title(text(ad.path("publicTitle")))
                .descriptionRo(Html.toText(text(ad.path("description"))))
                .descriptionRu(Html.toText(text(ad.path("descriptionRu"))))
                .price(price(ad.path("price")))
                .rooms(text(ad.path("rooms")))
                .areaTotal(number(ad.path("areaSqm"), "m2"))
                // A number of ari: "13.17" for a plot the description gives as 1 317 m².
                .landArea(number(ad.path("landArea"), "ar"))
                .floor(text(ad.path("floor")))
                .floorsTotal(text(ad.path("totalFloors")))
                .housingStock(housingStock(text(ad.path("housingStock"))))
                // "cotilet" in the layout list is the building's stone, not a series.
                .layout("cotilet".equals(text(ad.path("planning"))) ? null : label(SERIES, ad.path("planning")))
                .buildingMaterial("cotilet".equals(text(ad.path("planning"))) ? "Cotileț" : null)
                .condition(label(CONDITION, ad.path("renovation")))
                .developer(text(ad.path("complexName")))
                .bathrooms(text(ad.path("bathrooms")))
                .locality(city == null ? null : new Coded(null, city))
                .district(district == null ? null : new Coded(null, district))
                .street(text(location.path("address")))
                .pin(geo.path("lat").isNumber() && geo.path("lng").isNumber()
                        ? new GeoPoint(geo.path("lat").asDouble(), geo.path("lng").asDouble()) : null)
                .phones(phone == null ? List.of() : List.of(phone))
                .photoKeys(photos(ad.path("photos")))
                .amenities(amenities(ad.path("features")))
                .postedAt(instant(ad.path("createdAt")))
                .build();
    }

    static SourceCategory category(String kind) {
        return switch (kind) {
            case "apartment" -> SourceCategory.APARTMENTS;
            case "house" -> SourceCategory.HOUSES;
            default -> throw new IllegalArgumentException("CasaMare kind " + kind + " is not collected");
        };
    }

    private static RawPrice price(JsonNode p) {
        if (!p.isObject() || !p.path("amount").isNumber()) {
            return null;
        }
        return new RawPrice(p.path("amount").decimalValue(), text(p.path("currency")), null, null, null);
    }

    private static Quantity number(JsonNode n, String unit) {
        return n.isNumber() ? Quantity.of(n.decimalValue(), unit) : null;
    }

    /** A block's housing stock; "casa-particulara" (a private house) says nothing about it. */
    static String housingStock(String code) {
        if (code == null || code.equals("casa-particulara")) {
            return null;
        }
        return HOUSING_STOCK.getOrDefault(code, code);
    }

    private static String label(Map<String, String> labels, JsonNode code) {
        String c = text(code);
        return c == null ? null : labels.getOrDefault(c, c);
    }

    /** The photos' storage paths, without the signature that makes a link expire. */
    private static List<String> photos(JsonNode photos) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : photos) {
            String url = text(p.path("fallback")) != null ? text(p.path("fallback")) : text(p.path("webp"));
            if (url != null) {
                int q = url.indexOf('?');
                out.add(q > 0 ? url.substring(0, q) : url);
            }
        }
        return out;
    }

    private static Set<String> amenities(JsonNode features) {
        Set<String> out = new TreeSet<>();
        for (JsonNode f : features) {
            String code = AMENITIES.get(Text.fold(text(f)));
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
