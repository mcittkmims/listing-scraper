package md.apartmentlens.scraper.normalize;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.normalize.address.AddressNormalizer;
import md.apartmentlens.scraper.normalize.address.DefaultPins;
import md.apartmentlens.scraper.normalize.address.LocationNormalizer;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetTypes;
import md.apartmentlens.scraper.source.Coded;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.text.Text;

/**
 * Turns one source ad into clean, comparable fields. The same rules apply to every source.
 *
 * <p>Never guesses: a value no rule can read stays empty and is recorded as an {@link Issue}.
 * Raise {@link #VERSION} whenever a rule changes, so stored ads can be normalized again.
 */
public final class ListingNormalizer {

    public static final int VERSION = 9;

    private static final Pattern COUNT = Pattern.compile("(\\d{1,2})");
    private static final Pattern REGION_SUFFIX = Pattern.compile("\\s*\\b(mun\\.?|municipiul|r-nul|raionul|UTA)\\s*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final PriceNormalizer prices;
    private final AreaNormalizer areas = new AreaNormalizer();
    private final RoomsNormalizer rooms = new RoomsNormalizer();
    private final FloorNormalizer floors = new FloorNormalizer();
    private final PlaceGazetteer places;
    private final AddressNormalizer addresses;
    private final LocationNormalizer locations;
    private final PhoneNormalizer phones;

    public ListingNormalizer(PriceNormalizer prices, PlaceGazetteer places, StreetGazetteer streets,
                             PhoneNormalizer phones, DefaultPins defaultPins) {
        this.prices = prices;
        this.places = places;
        this.addresses = new AddressNormalizer(streets, places);
        this.locations = new LocationNormalizer(streets, defaultPins);
        this.phones = phones;
    }

    /**
     * @param priceDate the day the price was seen, for the exchange rate
     */
    public NormalizedListing normalize(SourceAd ad, LocalDate priceDate) {
        Issues issues = new Issues();
        NormalizedListing.Builder out = NormalizedListing.builder();

        Vocabulary.OFFER.code(ad.offerType(), issues).ifPresent(offer -> {
            if (!offer.equals("sale")) {
                issues.add("offer_type", ad.offerType(), "not_sale");
            }
        });

        // What is offered
        RoomsNormalizer.Rooms roomCount = rooms.rooms(ad.rooms(), issues);
        String propertyType = propertyType(ad, roomCount, issues);
        out.propertyType(propertyType)
                .rooms(roomCount.count())
                .roomsOpenEnded(roomCount.openEnded())
                .hasLivingRoom(rooms.livingRoom(ad.livingRoom(), issues));

        // Areas
        String descRo = Text.cleanMultiline(ad.descriptionRo());
        String descRu = Text.cleanMultiline(ad.descriptionRu());
        BigDecimal total = areas.area(ad.areaTotal(), AreaNormalizer.totalKind(propertyType), issues);
        total = areas.refineFromText(total, descRo, descRu);
        BigDecimal living = areas.area(ad.areaLiving(), AreaNormalizer.Kind.LIVING, issues);
        if (living != null && total != null && living.compareTo(total) > 0) {
            issues.add("area_living", living.toPlainString() + " > " + total.toPlainString(), "larger_than_total");
            living = null;
        }
        BigDecimal kitchen = areas.area(ad.areaKitchen(), AreaNormalizer.Kind.KITCHEN, issues);
        if (kitchen != null && total != null && kitchen.compareTo(total) >= 0) {
            issues.add("area_kitchen", kitchen.toPlainString() + " >= " + total.toPlainString(), "larger_than_total");
            kitchen = null;
        }
        out.areaTotalM2(total).areaLivingM2(living).areaKitchenM2(kitchen)
                .landAreaM2(areas.area(ad.landArea(), AreaNormalizer.Kind.LAND, issues))
                .ceilingHeightM(areas.ceilingHeight(ad.ceilingHeight(), issues));

        // Floors: a house has floors, a flat is on one
        boolean unit = propertyType.equals("apartment") || propertyType.equals("room");
        FloorNormalizer.Floor floor = floors.floor(unit ? ad.floor() : null, ad.floorsTotal(), issues);
        out.floor(floor.floor()).floorKind(floor.kind()).floorsTotal(floor.floorsTotal());

        // Price
        PriceNormalizer.Result price = prices.normalize(ad.price(), propertyType, total, priceDate, issues);
        out.priceAmount(price.amount()).priceCurrency(price.currency()).priceEur(price.eur())
                .pricePerM2Eur(price.eurPerM2()).priceNegotiable(price.negotiable()).downPayment(price.downPayment());

        // Seller and building
        out.sellerType(Vocabulary.SELLER.code(ad.sellerType(), issues).orElse(null))
                .sellerName(Text.clean(ad.sellerName()))
                .sellerAccount(Text.clean(ad.sellerAccount()))
                .title(Text.clean(ad.title()))
                .descriptionRo(descRo)
                .descriptionRu(descRu)
                .housingStock(Vocabulary.HOUSING_STOCK.code(ad.housingStock(), issues).orElse(null))
                .buildingMaterial(Vocabulary.MATERIAL.code(ad.buildingMaterial(), issues).orElse(null))
                .buildingSeries(Vocabulary.SERIES.code(ad.layout(), issues).orElse(null))
                .condition(Vocabulary.CONDITION.code(ad.condition(), issues).orElse(null))
                .parking(Vocabulary.PARKING.code(ad.parking(), issues).orElse(null))
                .developer(Text.clean(ad.developer()))
                .bathrooms(count("bathrooms", ad.bathrooms(), issues))
                .balconies(count("balconies", ad.balconies(), issues));

        // Address and map point
        Coded region = ad.region();
        Coded locality = ad.locality();
        Coded district = ad.district();
        String regionLabel = region == null ? null : Text.clean(region.label());
        String localityLabel = locality == null ? null : Text.clean(locality.label());
        String districtLabel = district == null ? null : Text.clean(district.label());
        out.region(regionName(regionLabel)).locality(localityLabel).district(districtLabel)
                .regionCode(region == null ? null : region.code())
                .localityCode(locality == null ? null : locality.code())
                .districtCode(district == null ? null : district.code());

        Place place = places.settlement(localityLabel, regionLabel, ad.pin()).orElse(null);
        if (place == null && localityLabel != null) {
            issues.add("locality", localityLabel, "not_in_gazetteer");
        }
        String streetRaw = ad.street();
        Place village = villageInStreet(ad, place, districtLabel);
        if (village != null) {
            place = village;
            streetRaw = null;
        }
        if (place != null) {
            out.locality(place.nameRo()); // one spelling for every source: "Stăuceni", not "Stauceni"
        }
        GeoPoint pin = locations.checkPin(ad.pin(), place, issues);
        // A pin the site placed only near the property never picks a street or a building; it places the ad
        // only when nothing more precise is known.
        boolean approximate = ad.pinRadiusM() != null;
        GeoPoint onProperty = approximate ? null : pin;
        Place districtPlace = places.district(districtLabel, place).orElse(null);
        AddressNormalizer.Address address = addresses.normalize(streetRaw, ad.houseNumber(), place, onProperty,
                issues);
        LocationNormalizer.Location location = locations.locate(onProperty, address, districtPlace, place, issues);
        if (approximate && pin != null) {
            location = locations.near(location, pin, ad.pinRadiusM());
        }
        out.streetRaw(Text.clean(ad.street())).houseNumberRaw(Text.clean(ad.houseNumber()))
                .streetType(address.streetType()).streetName(address.streetName())
                .streetMatch(address.streetMatch()).areaName(address.areaName()).houseNumber(address.houseNumber())
                .point(location.point()).sellerPin(ad.pin()).pinRadiusM(ad.pinRadiusM())
                .locationSource(location.source())
                .locationPrecision(location.precision());

        // Contacts and media
        out.phoneHashes(phones.hashes(ad.phones(), issues))
                .photoKeys(List.copyOf(ad.photoKeys()))
                .photoUrls(List.copyOf(ad.photoUrls()))
                .hasVideo(ad.hasVideo())
                .amenities(ad.amenities().stream().sorted().toList())
                .crosslinks(List.copyOf(ad.crosslinks()));

        return out.issues(issues.list()).build();
    }

    /** How far from the ad's locality a village written as the street can be. */
    static final double VILLAGE_IN_STREET_KM = 25;

    /**
     * Some sources name a district or a nearby town and write the village where the street goes ("Ialoveni",
     * "Dănceni"). When the ad has no pin on the property and no city district, a bare street text (no type such
     * as "str.") that is exactly the name of a settlement nearby, and of no street of the locality itself, is
     * that settlement.
     */
    private Place villageInStreet(SourceAd ad, Place place, String districtLabel) {
        String street = Text.clean(ad.street());
        if (place == null || street == null || districtLabel != null || (ad.pin() != null && ad.pinRadiusM() == null)) {
            return null;
        }
        StreetTypes.Split split = StreetTypes.split(street);
        if (split.type() != null || split.name() == null) {
            return null;
        }
        return places.settlementExactly(split.name(), place.point())
                .filter(v -> !v.equals(place) && v.point().kmTo(place.point()) < VILLAGE_IN_STREET_KM)
                .filter(v -> !addresses.isStreetIn(split.name(), place))
                .orElse(null);
    }

    private static String propertyType(SourceAd ad, RoomsNormalizer.Rooms roomCount, Issues issues) {
        return switch (ad.category()) {
            case APARTMENTS -> roomCount.singleRoomOffer() ? "room" : "apartment";
            case HOUSES -> ad.propertyType() == null ? "house"
                    : Vocabulary.HOUSE_TYPE.code(ad.propertyType(), issues).orElse("house");
            case COUNTRY_HOUSES -> "country_house";
        };
    }

    /** "Chișinău mun." -> "Chișinău". */
    static String regionName(String label) {
        if (label == null) {
            return null;
        }
        String s = REGION_SUFFIX.matcher(label).replaceAll(" ").strip();
        return s.isEmpty() ? label : s;
    }

    /** Bathrooms or balconies: "2", "4 și mai multe", "Nu", "Fără". */
    static Integer count(String field, String label, Issues issues) {
        String clean = Text.clean(label);
        if (clean == null) {
            return null;
        }
        String key = Text.fold(clean);
        if (key.equals("nu") || key.startsWith("fara") || key.equals("нет") || key.startsWith("без")) {
            return 0;
        }
        Matcher m = COUNT.matcher(key);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        issues.add(field, clean, "unparsed");
        return null;
    }
}
