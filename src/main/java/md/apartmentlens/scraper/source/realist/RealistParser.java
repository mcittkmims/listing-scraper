package md.apartmentlens.scraper.source.realist;

import java.util.ArrayList;
import java.util.List;
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
 * Reads the extract of one Realist ad page (see {@link RealistSource#extract}) into a {@link SourceAd}.
 *
 * <p>The page title is "Vânzare, apartament, 2 camere, strada Grenoble, Botanica": the street, when given,
 * is the part with a street type. A Chișinău sector is named in the breadcrumbs ("..., zona Botanica").
 */
final class RealistParser {

    /** How far off the site moves a pin it marks as fuzzy; not published, so a cautious guess. */
    static final int FUZZY_RADIUS_M = 500;
    static final Pattern ZONE = Pattern.compile(",\\s*zona\\s+(.+)$");
    static final Pattern ID = Pattern.compile("-cp(\\d+)/?$");

    SourceAd parse(JsonNode page) {
        JsonNode listing = page.path("listing");
        JsonNode features = page.path("features");
        JsonNode address = listing.path("address");
        SourceCategory category = category(listing);
        String url = text(listing.path("url"));
        Matcher id = ID.matcher(url == null ? "" : url);
        String locality = text(address.path("addressLocality"));

        JsonNode map = page.path("map");
        GeoPoint pin = Numbers.parseDecimal(text(map.path("lat"))).flatMap(lat -> Numbers.parseDecimal(text(map.path("lng")))
                .map(lng -> new GeoPoint(lat.doubleValue(), lng.doubleValue()))).orElse(null);
        List<String> phones = new ArrayList<>();
        page.path("phones").forEach(p -> phones.add(text(p)));
        JsonNode offer = listing.path("offers");

        return SourceAd.builder()
                .externalId(id.find() ? id.group(1) : null)
                .url(url)
                .category(category)
                .offerType(text(offer.path("businessFunction")) != null && text(offer.path("businessFunction")).endsWith("#Sell")
                        ? "Vânzare" : text(offer.path("businessFunction")))
                .sellerType("Agenție")
                .sellerName("Realist")
                .title(text(page.path("title")))
                .descriptionRo(text(listing.path("description")))
                .price(offer.path("price").isNumber()
                        ? new RawPrice(offer.path("price").decimalValue(), text(offer.path("priceCurrency")), null, null, null)
                        : null)
                .rooms(text(listing.path("numberOfRooms")))
                .propertyType(category == SourceCategory.HOUSES ? text(features.path("Tip casă")) : null)
                .areaTotal(listing.path("floorSize").path("value").isNumber()
                        ? Quantity.of(listing.path("floorSize").path("value").decimalValue(), "m2") : null)
                .landArea(land(text(features.path("Suprafață teren"))))
                .floor(text(features.path("Etaj")))
                .floorsTotal(text(features.path("Număr etaje clădire")))
                .housingStock(newBuilding(text(features.path("Construcție nouă"))))
                .condition(text(features.path("Stare interior")))
                .bathrooms(text(listing.path("numberOfBathroomsTotal")))
                .balconies(text(features.path("Număr balcoane")))
                .region(text(address.path("addressRegion")) == null ? null : new Coded(null, text(address.path("addressRegion"))))
                .locality(locality == null ? null : new Coded(null, locality))
                .district(zone(page.path("breadcrumbs")))
                .street(street(text(page.path("title"))))
                .pin(pin)
                .pinRadiusM(pin != null && !"False".equalsIgnoreCase(text(map.path("fuzzy"))) ? FUZZY_RADIUS_M : null)
                .phones(phones)
                .photoKeys(listing.path("image").valueStream().map(RealistParser::text).toList())
                .build();
    }

    /** Flats and houses for sale; null for other kinds of property. */
    static SourceCategory category(JsonNode listing) {
        JsonNode type = listing.path("@type");
        List<String> types = type.isArray() ? type.valueStream().map(JsonNode::asString).toList() : List.of(type.asString());
        if (types.contains("Apartment")) {
            return SourceCategory.APARTMENTS;
        }
        if (types.contains("House") || types.contains("SingleFamilyResidence")) {
            return SourceCategory.HOUSES;
        }
        return null;
    }

    /** The street part of the title: the part after the rooms that starts with a street type. */
    static String street(String title) {
        if (title == null) {
            return null;
        }
        String[] parts = title.split(",");
        for (int i = 2; i < parts.length; i++) {
            String part = parts[i].strip();
            StreetTypes.Split split = StreetTypes.split(part);
            if (split.type() != null && split.name() != null) {
                return part;
            }
        }
        return null;
    }

    /** "Apartamente de vânzare Chișinău, zona Botanica" in the breadcrumbs: the sector. */
    static Coded zone(JsonNode breadcrumbs) {
        for (JsonNode crumb : breadcrumbs) {
            Matcher m = ZONE.matcher(crumb.asString(""));
            if (m.find()) {
                return new Coded(null, m.group(1).strip());
            }
        }
        return null;
    }

    /**
     * The plot's area. The site labels it "mp" but gives ari ("2.7 mp" for a house plot); a value too small
     * for square metres is read as ari, a larger one stays square metres.
     */
    static Quantity land(String text) {
        return Numbers.parseDecimal(text).map(v -> Quantity.of(v, v.doubleValue() < 100 ? "ar" : "m2")).orElse(null);
    }

    static String newBuilding(String yesNo) {
        if (yesNo == null) {
            return null;
        }
        return switch (Text.fold(yesNo)) {
            case "da" -> "Construcție nouă";
            case "nu" -> "Secundar";
            default -> yesNo;
        };
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = n.isString() ? n.stringValue() : n.asString();
        return s == null || s.isBlank() ? null : s.strip();
    }
}
