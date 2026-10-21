package md.apartmentlens.scraper.source.ninetynine;

import static md.apartmentlens.scraper.source.ninetynine.NineNineFeatures.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/** Reads one 999.md ad (as returned by searchAds or advert) into a {@link SourceAd}. No cleaning here. */
final class NineNineParser {

    /** 999.md's image server; 900x900 is the largest size it serves. */
    static final String PHOTO_URL = "https://i.simpalsmedia.com/999.md/BoardImages/900x900/";

    SourceAd parse(JsonNode ad) {
        String id = ad.path("id").asString();
        SourceCategory category = category(ad.path("subCategory").path("id").asInt());
        JsonNode body = value(ad, BODY);
        JsonNode company = value(ad, COMPANY);

        return SourceAd.builder()
                .externalId(id)
                .url("https://999.md/ro/" + id)
                .category(category)
                .offerType(label(ad, OFFER_TYPE))
                .sellerType(label(ad, SELLER_TYPE))
                .sellerName(company.isMissingNode() ? null : text(company.path("brand")))
                .sellerAccount(text(ad.path("owner").path("id")))
                .title(text(ad.path("title")))
                .descriptionRo(text(body.path("ro")))
                .descriptionRu(text(body.path("ru")))
                .price(price(value(ad, PRICE)))
                .rooms(category == SourceCategory.APARTMENTS ? label(ad, ROOMS_APARTMENT) : number(ad, ROOMS_HOUSE))
                .livingRoom(label(ad, LIVING_ROOM))
                .propertyType(label(ad, HOUSE_TYPE))
                .areaTotal(quantity(ad, AREA_TOTAL))
                .areaLiving(quantity(ad, AREA_LIVING))
                .areaKitchen(quantity(ad, AREA_KITCHEN))
                .landArea(quantity(ad, AREA_LAND))
                .ceilingHeight(quantity(ad, CEILING_HEIGHT))
                .floor(label(ad, FLOOR))
                .floorsTotal(label(ad, FLOORS_TOTAL))
                .housingStock(label(ad, HOUSING_STOCK))
                .buildingMaterial(label(ad, BUILDING_MATERIAL))
                .layout(label(ad, LAYOUT))
                .condition(category == SourceCategory.APARTMENTS ? label(ad, APARTMENT_CONDITION)
                        : label(ad, HOUSE_CONDITION))
                .developer(label(ad, DEVELOPER))
                .bathrooms(label(ad, BATHROOMS))
                .balconies(label(ad, BALCONIES))
                .parking(label(ad, PARKING))
                .region(coded(ad, REGION))
                .locality(coded(ad, LOCALITY))
                .district(coded(ad, DISTRICT))
                .street(text(value(ad, STREET)))
                .houseNumber(text(value(ad, HOUSE_NUMBER)))
                .pin(pin(value(ad, MAP_POINT)))
                .phones(strings(value(ad, CONTACTS).path("phone_numbers")))
                .photoKeys(strings(value(ad, IMAGES)))
                .photoUrls(strings(value(ad, IMAGES)).stream().map(k -> PHOTO_URL + k).toList())
                .hasVideo(!value(ad, VIDEOS).isMissingNode() || !value(ad, UPLOADED_VIDEO).isMissingNode())
                .amenities(amenities(ad))
                .postedAt(instant(ad.path("posted")))
                .republishedAt(instant(ad.path("reseted")))
                .build();
    }

    static SourceCategory category(int subCategory) {
        return switch (subCategory) {
            case 1404 -> SourceCategory.APARTMENTS;
            case 1406 -> SourceCategory.HOUSES;
            case 6678 -> SourceCategory.COUNTRY_HOUSES;
            default -> throw new IllegalArgumentException("999.md subcategory " + subCategory + " is not collected");
        };
    }

    /** The feature's value, or a missing node when the ad does not have the feature (absent or null). */
    private static JsonNode value(JsonNode ad, int feature) {
        JsonNode v = ad.path("f" + feature).path("value");
        return v.isNull() ? MissingNode.getInstance() : v;
    }

    private static String label(JsonNode ad, int feature) {
        JsonNode v = value(ad, feature);
        if (v.isObject()) {
            return text(v.path("translated"));
        }
        return text(v);
    }

    private static Coded coded(JsonNode ad, int feature) {
        JsonNode v = value(ad, feature);
        if (!v.isObject()) {
            return null;
        }
        JsonNode code = v.path("value");
        return new Coded(code.isNumber() ? code.asInt() : null, text(v.path("translated")));
    }

    private static String number(JsonNode ad, int feature) {
        JsonNode v = value(ad, feature);
        if (v.isNumber()) {
            return v.asString();
        }
        return label(ad, feature);
    }

    private static Quantity quantity(JsonNode ad, int feature) {
        JsonNode v = value(ad, feature);
        if (v.isObject()) {
            JsonNode n = v.path("value");
            return Quantity.of(n.isNumber() ? n.decimalValue() : null, text(v.path("unit")));
        }
        if (v.isNumber()) {
            return Quantity.of(v.decimalValue(), null);
        }
        return null;
    }

    private static RawPrice price(JsonNode v) {
        if (!v.isObject()) {
            return null;
        }
        JsonNode amount = v.path("value");
        JsonNode down = v.path("down_payment");
        String mode = text(v.path("mode"));
        Boolean negotiable = v.path("bargain").asBoolean(false) || "PM_NEGOTIABLE".equals(mode) ? Boolean.TRUE
                : Boolean.FALSE;
        return new RawPrice(amount.isNumber() ? amount.decimalValue() : null, text(v.path("unit")), null, negotiable,
                down.isNumber() ? down.decimalValue() : null);
    }

    private static GeoPoint pin(JsonNode v) {
        if (!v.isObject() || !v.path("lat").isNumber() || !v.path("lon").isNumber()) {
            return null; // the seller opened the map but placed no pin
        }
        return new GeoPoint(v.path("lat").asDouble(), v.path("lon").asDouble());
    }

    private static Set<String> amenities(JsonNode ad) {
        TreeSet<String> out = new TreeSet<>();
        for (Map.Entry<Integer, String> e : AMENITIES.entrySet()) {
            if (value(ad, e.getKey()).asBoolean(false)) {
                out.add(e.getValue());
            }
        }
        for (int feature : new int[] {PLUMBING, SEWERAGE, GAS}) {
            JsonNode v = value(ad, feature);
            String code = UTILITY_OPTIONS.get(v.path("value").asInt(-1));
            if (code != null) {
                out.add(code);
            }
        }
        return out;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode n : array) {
                String s = text(n);
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static Instant instant(JsonNode n) {
        String s = text(n);
        if (s == null) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException e) {
            return OffsetDateTime.parse(s).toInstant();
        }
    }

    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return null;
        }
        String s = n.isString() ? n.stringValue() : n.asString();
        return s == null || s.isBlank() ? null : s;
    }
}
