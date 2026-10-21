package md.apartmentlens.scraper.source.romanescu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.normalize.address.StreetTypes;
import md.apartmentlens.scraper.source.Coded;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.source.Quantity;
import md.apartmentlens.scraper.source.RawPrice;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.SourceCategory;
import md.apartmentlens.scraper.text.Numbers;
import md.apartmentlens.scraper.text.Text;
import tools.jackson.databind.JsonNode;

/**
 * Reads the extract of one Romanescu ad page (see {@link RomanescuSource#extract}) into a {@link SourceAd}.
 *
 * <p>The title is free text ("Casă spațioasă în Durlești | str. Plopilor | 175 mp | 3 ari"): a street is taken
 * from it only where it is written with its type ("str.", "bd."). The place comes from the breadcrumbs:
 * "Vanzare › Apartamente › Chisinau › Botanica".
 */
final class RomanescuParser {

    static final Pattern ID = Pattern.compile("-(\\d+)$");
    /** "Etaj 10", "Parter", "Demisol". */
    static final Pattern FLOOR = Pattern.compile("(?i)^etaj\\s+(.+)$");
    /** "P+13", "S+P+2", "P+1+M": ground floor, floors above it, a mansard. */
    static final Pattern HEIGHT = Pattern.compile("(?i)^(?:[SD]\\+)?P(?:\\+(\\d+))?(\\+M)?$");
    static final Pattern TITLE_STREET = Pattern.compile(
            "(?iu)\\b((?:str|bd|bul|șos|sos|str-la|stradela|strada|bulevardul|șoseaua|calea)\\.?\\s+[^,|!;()–]+)");
    static final Map<String, String> AMENITIES = folded(Map.ofEntries(
            Map.entry("Apa", "plumbing"),
            Map.entry("Canalizare", "sewerage"),
            Map.entry("Gaz", "gas"),
            Map.entry("CATV", "cable_tv"),
            Map.entry("Acces internet", "internet"),
            Map.entry("Centrala proprie", "autonomous_heating"),
            Map.entry("Ferestre PVC", "double_glazing"),
            Map.entry("Usa intrare Metal", "armored_door"),
            Map.entry("Interfon", "intercom"),
            Map.entry("Videointerfon", "intercom"),
            Map.entry("Lift", "elevator"),
            Map.entry("Aer conditionat", "air_conditioning"),
            Map.entry("Parchet", "parquet"),
            Map.entry("Mobilat", "furnished"),
            Map.entry("Garaj", "garage"),
            Map.entry("Piscina", "pool"),
            Map.entry("Terasa", "terrace"),
            Map.entry("Beci", "cellar"),
            Map.entry("Incalzire in pardoseala", "floor_heating"),
            Map.entry("Semineu", "fireplace")));

    SourceAd parse(JsonNode page) {
        JsonNode offer = page.path("offer");
        JsonNode item = offer.path("itemOffered");
        JsonNode features = page.path("features");
        JsonNode crumbs = page.path("breadcrumbs");
        String url = text(offer.path("url"));
        Matcher id = ID.matcher(url == null ? "" : url);
        String kind = crumbs.size() > 1 ? crumbs.get(1).asString() : null;
        String locality = crumbs.size() > 2 ? crumbs.get(2).asString() : null;
        String district = crumbs.size() > 3 ? crumbs.get(3).asString() : null;

        JsonNode map = page.path("map");
        GeoPoint pin = Numbers.parseDecimal(text(map.path("lat"))).flatMap(lat -> Numbers.parseDecimal(text(map.path("lng")))
                .map(lng -> new GeoPoint(lat.doubleValue(), lng.doubleValue()))).orElse(null);
        Integer radius = "1".equals(text(map.path("exact"))) ? null
                : Numbers.parseDecimal(text(map.path("range"))).map(r -> Math.max(100, r.intValue())).orElse(1000);

        String agency = text(page.path("agencyPhone"));
        List<String> phones = new ArrayList<>();
        for (JsonNode tel : page.path("phones")) {
            String t = text(tel);
            if (t != null && !t.equals(agency)) {
                phones.add(t);
            }
        }
        Matcher floor = FLOOR.matcher(String.valueOf(text(features.path("Etaj"))));
        String title = text(page.path("title"));

        return SourceAd.builder()
                .externalId(id.find() ? id.group(1) : null)
                .url(url)
                .category(category(kind))
                .offerType(crumbs.size() > 0 ? crumbs.get(0).asString() : null)
                .sellerType("Agenție")
                .sellerName("Romanescu")
                .title(title)
                .descriptionRo(text(item.path("description")))
                .price(Numbers.parseDecimal(text(offer.path("price")))
                        .map(p -> new RawPrice(p, text(offer.path("priceCurrency")), null, null, null)).orElse(null))
                .rooms(text(features.path("Nr. camere")))
                .areaTotal(area(text(features.path("S. construita")) != null ? text(features.path("S. construita"))
                        : text(features.path("S. utila"))))
                .landArea(Quantity.ofText(text(features.path("S. teren"))))
                .floor(floor.matches() ? floor.group(1) : text(features.path("Etaj")))
                .floorsTotal(floorsTotal(text(features.path("Regim inaltime"))))
                .buildingMaterial(text(features.path("Structura")))
                .bathrooms(text(features.path("Nr. bai")))
                .balconies(text(features.path("Nr. balcoane")))
                .locality(locality == null ? null : new Coded(null, locality))
                .district(district == null ? null : new Coded(null, district))
                .street(street(title))
                .pin(pin)
                .pinRadiusM(pin == null ? null : radius)
                .phones(phones)
                .photoKeys(item.path("image").valueStream().map(RomanescuParser::text).toList())
                .amenities(amenities(page.path("specs")))
                .build();
    }

    static SourceCategory category(String kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "Apartamente" -> SourceCategory.APARTMENTS;
            case "Case" -> SourceCategory.HOUSES;
            default -> throw new IllegalArgumentException("Romanescu category " + kind + " is not collected");
        };
    }

    private static Quantity area(String text) {
        return text == null ? null : Quantity.ofText(text);
    }

    /** "P+1" is two floors; "P+13" for a block is given as 13 floors by the site's own cards, so it is not read. */
    static String floorsTotal(String height) {
        if (height == null) {
            return null;
        }
        if (height.matches("\\d+")) {
            return height;
        }
        Matcher m = HEIGHT.matcher(height.replace(" ", ""));
        if (m.matches() && (m.group(1) == null || Integer.parseInt(m.group(1)) <= 3)) {
            // A house: the ground floor, up to three floors and a mansard are levels.
            return String.valueOf(1 + (m.group(1) == null ? 0 : Integer.parseInt(m.group(1))) + (m.group(2) == null ? 0 : 1));
        }
        return null;
    }

    /** The street in a free-text title, only where it is written with its type: "Durlești, str. Cartușa, ...". */
    static String street(String title) {
        if (title == null) {
            return null;
        }
        Matcher m = TITLE_STREET.matcher(title);
        while (m.find()) {
            String part = m.group(1).strip().replaceFirst("[.!]+$", "");
            StreetTypes.Split split = StreetTypes.split(part);
            if (split.type() != null && split.name() != null) {
                return part;
            }
        }
        return null;
    }

    private static Set<String> amenities(JsonNode specs) {
        Set<String> out = new TreeSet<>();
        for (JsonNode s : specs) {
            String code = AMENITIES.get(Text.fold(s.asString()));
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

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = n.isString() ? n.stringValue() : n.asString();
        return s == null || s.isBlank() ? null : s.strip();
    }
}
