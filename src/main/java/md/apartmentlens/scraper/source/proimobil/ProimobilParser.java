package md.apartmentlens.scraper.source.proimobil;

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
import md.apartmentlens.scraper.text.Text;
import tools.jackson.databind.JsonNode;

/**
 * Reads one Proimobil property into a {@link SourceAd}. The API's codes are turned into the labels the
 * website shows, which the normalizer knows; an unknown code is passed on as it is and reported there.
 */
final class ProimobilParser {

    static final String CHISINAU = "Chișinău";
    /** The API's "city" for the suburbs of Chișinău; their settlement is the "region". */
    static final String SUBURBS = "Suburbie";

    static final Map<String, String> CONDITION = Map.of(
            "euro", "Euroreparație",
            "white", "Variantă albă",
            "gray", "Variantă sură",
            "living", "Stare locativă",
            "individual", "Design individual",
            "undone", "Construcție nefinisată");
    /** The building's "Tip construcție": new or old. */
    static final Map<String, String> HOUSING_STOCK = Map.of("new", "Construcție nouă", "old", "Secundar");
    static final Map<String, String> MATERIAL = Map.of("concrete", "Beton", "combined", "Combinat", "brick", "Cărămidă");
    static final Map<String, String> SERIES = Map.of(
            "ind", "Individuală",
            "ms", "Ms",
            "vartz", "Varnițkaia",
            "hr", "Hrușciovka",
            "ground", "Pe pământ");
    static final Map<String, String> PARKING = Map.of("open", "Deschisă", "underground", "Subterană", "garage", "Garaj");
    /** The website's feature names of the property itself (places nearby are not amenities), folded. */
    static final Map<String, String> AMENITIES = folded(Map.ofEntries(
            Map.entry("Încălzire autonomă", "autonomous_heating"),
            Map.entry("Ușă blindată", "armored_door"),
            Map.entry("Mobilat", "furnished"),
            Map.entry("Lift", "elevator"),
            Map.entry("Gata pentru intrarea", "move_in_ready"),
            Map.entry("Gata pentru intrare", "move_in_ready"),
            Map.entry("Interfon", "intercom"),
            Map.entry("Cu electrocasnice", "appliances"),
            Map.entry("Teren de joacă", "playground"),
            Map.entry("Cablu TV", "cable_tv"),
            Map.entry("Grădină / seră", "garden"),
            Map.entry("Apeduct", "plumbing"),
            Map.entry("Gazificat", "gas"),
            Map.entry("Garaj", "garage"),
            Map.entry("Sistem autonom de canalizare", "sewerage"),
            Map.entry("Terasă", "terrace"),
            Map.entry("Beci/subsol", "cellar"),
            Map.entry("Linie telefonică", "phone_line"),
            Map.entry("Internet", "internet"),
            Map.entry("Parchet", "parquet"),
            Map.entry("Aer conditionat", "air_conditioning"),
            Map.entry("Climatizatoare", "air_conditioning"),
            Map.entry("Supraveghere video", "video_surveillance"),
            Map.entry("Încălzire prin pardoseală", "floor_heating"),
            Map.entry("Pardoseli calde", "floor_heating"),
            Map.entry("Saună", "sauna"),
            Map.entry("Piscină", "pool"),
            Map.entry("Rîu / iaz", "near_water"),
            Map.entry("Cămin", "fireplace"),
            Map.entry("Generator electric", "generator"),
            Map.entry("Sistemul de alarmă", "alarm"),
            Map.entry("Sistem de alarmă", "alarm"),
            Map.entry("Poarta cu control de la distanta", "remote_gate"),
            Map.entry("Sistem de \"casa inteligentă\"", "smart_home"),
            Map.entry("Sistem de purificare a apei", "water_purification"),
            Map.entry("O parte a casei", "part_of_house"),
            Map.entry("Cameră pentru funcţionarii de securitate", "staff_room"),
            Map.entry("Intrare separata", "separate_entrance")));

    SourceAd parse(JsonNode ad) {
        String category = ad.path("category").asString();
        JsonNode ro = ad.path("i18n").path("ro");
        JsonNode ru = ad.path("i18n").path("ru");
        JsonNode embedded = ad.path("_embedded");
        String city = text(embedded.path("city").path("i18n").path("ro").path("name"));
        String area = text(embedded.path("region").path("i18n").path("ro").path("name"));
        boolean capital = CHISINAU.equals(city);
        // The API's "city" is a municipality or district, its "region" the settlement or, in Chișinău, the sector.
        String region = capital || SUBURBS.equals(city) ? CHISINAU : city;
        String locality = capital ? CHISINAU : area;

        JsonNode agents = embedded.path("agents");
        List<String> phones = new ArrayList<>();
        for (JsonNode agent : agents) {
            String phone = text(agent.path("phone"));
            if (phone != null) {
                phones.add(phone);
            }
        }
        String copy = text(ad.path("999").path("id"));

        return SourceAd.builder()
                .externalId(ad.path("id").asString())
                .url(url(category, text(ro.path("url"))))
                .category(category(category))
                .offerType("sell".equals(text(ad.path("offer"))) ? "Vânzare" : text(ad.path("offer")))
                .sellerType("Agenție")
                .sellerName("Proimobil")
                .sellerAccount(agents.isEmpty() ? null : text(agents.get(0).path("surrogateId")))
                .title(text(ad.path("999").path("title")))
                .descriptionRo(Html.toText(text(ro.path("description"))))
                .descriptionRu(Html.toText(text(ru.path("description"))))
                .price(price(ad.path("price")))
                .rooms(text(ad.path("rooms")))
                .areaTotal(area(ad.path("surface"), null))
                .areaLiving(area(ad.path("livingSurface"), null))
                .areaKitchen(area(ad.path("kitchenSurface"), null))
                // Shown as "6 ar" although the API says m2.
                .landArea(area(ad.path("landSurface"), "ar"))
                .ceilingHeight(positive(ad.path("ceilingHeight")) ? Quantity.of(ad.path("ceilingHeight").decimalValue(), "m") : null)
                .floor(text(ad.path("floor")))
                .floorsTotal(text(ad.path("numberOfFloors")))
                .housingStock(label(HOUSING_STOCK, ad.path("condition")))
                .buildingMaterial(label(MATERIAL, ad.path("base")))
                .layout(label(SERIES, ad.path("layout")))
                .condition(label(CONDITION, ad.path("state")))
                .bathrooms(text(ad.path("bathrooms")))
                .balconies(text(ad.path("balcony")))
                .parking(label(PARKING, ad.path("parking")))
                .region(region == null ? null : new Coded(null, region))
                .locality(locality == null ? null : new Coded(null, locality))
                .district(capital && area != null ? new Coded(null, area) : null)
                .street(text(ro.path("address")))
                .phones(phones)
                .photoKeys(photos(ad))
                .amenities(amenities(ro.path("features")))
                .crosslinks(copy == null ? List.of() : List.of("999md:" + copy))
                .postedAt(instant(ad.path("createdAt")))
                .build();
    }

    static SourceCategory category(String category) {
        return switch (category) {
            case "apartment" -> SourceCategory.APARTMENTS;
            case "house" -> SourceCategory.HOUSES;
            default -> throw new IllegalArgumentException("Proimobil category " + category + " is not collected");
        };
    }

    static String url(String category, String slug) {
        String path = switch (category) {
            case "apartment" -> "apartamente-in-chisinau";
            case "house" -> "case-in-chisinau";
            default -> throw new IllegalArgumentException("Proimobil category " + category + " is not collected");
        };
        return "https://proimobil.md/" + path + "/" + slug;
    }

    private static RawPrice price(JsonNode p) {
        if (!p.isObject() || !p.path("amount").isNumber()) {
            return null;
        }
        return new RawPrice(p.path("amount").decimalValue(), text(p.path("currency")), null, null, null);
    }

    private static Quantity area(JsonNode a, String unit) {
        if (!a.isObject() || !a.path("value").isNumber()) {
            return null;
        }
        return Quantity.of(a.path("value").decimalValue(), unit != null ? unit : text(a.path("units")));
    }

    private static String label(Map<String, String> labels, JsonNode code) {
        String c = text(code);
        return c == null ? null : labels.getOrDefault(c, c);
    }

    private static List<String> photos(JsonNode ad) {
        List<String> out = new ArrayList<>();
        String cover = text(ad.path("image"));
        if (cover != null) {
            out.add(cover);
        }
        for (JsonNode g : ad.path("gallery")) {
            String key = text(g);
            if (key != null && !out.contains(key)) {
                out.add(key);
            }
        }
        return out;
    }

    private static Set<String> amenities(JsonNode features) {
        Set<String> out = new TreeSet<>();
        for (JsonNode f : features) {
            String code = AMENITIES.get(Text.fold(text(f.path("name"))));
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

    private static boolean positive(JsonNode n) {
        return n.isNumber() && n.decimalValue().signum() > 0;
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
