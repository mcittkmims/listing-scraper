package md.apartmentlens.scraper.normalize;

import java.math.BigDecimal;
import java.util.List;
import md.apartmentlens.scraper.source.GeoPoint;

/**
 * An ad after normalization, in the shape of an {@code ingest.listing} row.
 *
 * @param propertyType apartment, room, house, townhouse, duplex, villa or country_house
 * @param sellerType owner, agency, developer or bank
 * @param priceAmount the price as published
 * @param priceCurrency ISO code: EUR, USD or MDL
 * @param priceEur price in EUR at the BNM rate; empty when the price is not plausible
 * @param roomsOpenEnded true for "5 rooms or more"
 * @param floor 0 is the semi-basement, -1 the basement
 * @param floorKind basement, semi_basement, attic or penthouse
 * @param housingStock new_build or secondary
 * @param streetName official Romanian street name, without the type
 * @param streetMatch how the street was recognized: exact, alias, partial, fuzzy, nearby, from_pin or unresolved
 * @param areaName a neighbourhood, complex or cottage area written where the street belongs
 * @param sellerPin the pin as the seller placed it, also when it is not used as the location
 * @param pinRadiusM null for a pin on the property; for a pin the site placed only near it, how far off it can be
 * @param locationSource pin, address, street or place
 * @param locationPrecision building, street, district or locality
 * @param phoneHashes keyed hashes of the +373 numbers
 * @param crosslinks the same ad on other sites, as the source names them: "999md:105689225"
 * @param issues values that could not be read, with the reason
 */
public record NormalizedListing(
        String propertyType,
        String sellerType,
        String sellerName,
        String sellerAccount,
        String title,
        String descriptionRo,
        String descriptionRu,
        BigDecimal priceAmount,
        String priceCurrency,
        BigDecimal priceEur,
        BigDecimal pricePerM2Eur,
        Boolean priceNegotiable,
        BigDecimal downPayment,
        Integer rooms,
        boolean roomsOpenEnded,
        Boolean hasLivingRoom,
        BigDecimal areaTotalM2,
        BigDecimal areaLivingM2,
        BigDecimal areaKitchenM2,
        BigDecimal landAreaM2,
        Integer floor,
        String floorKind,
        Integer floorsTotal,
        Integer bathrooms,
        Integer balconies,
        BigDecimal ceilingHeightM,
        String housingStock,
        String buildingMaterial,
        String buildingSeries,
        String condition,
        String developer,
        String parking,
        String region,
        String locality,
        String district,
        Integer regionCode,
        Integer localityCode,
        Integer districtCode,
        String streetRaw,
        String houseNumberRaw,
        String streetType,
        String streetName,
        String streetMatch,
        String areaName,
        String houseNumber,
        GeoPoint point,
        GeoPoint sellerPin,
        Integer pinRadiusM,
        String locationSource,
        String locationPrecision,
        List<byte[]> phoneHashes,
        List<String> photoKeys,
        List<String> photoUrls,
        boolean hasVideo,
        List<String> amenities,
        List<String> crosslinks,
        List<Issue> issues) {

    public static Builder builder() {
        return new Builder();
    }

    /** Builder; unset fields stay empty. */
    public static final class Builder {

        private String propertyType;
        private String sellerType;
        private String sellerName;
        private String sellerAccount;
        private String title;
        private String descriptionRo;
        private String descriptionRu;
        private BigDecimal priceAmount;
        private String priceCurrency;
        private BigDecimal priceEur;
        private BigDecimal pricePerM2Eur;
        private Boolean priceNegotiable;
        private BigDecimal downPayment;
        private Integer rooms;
        private boolean roomsOpenEnded;
        private Boolean hasLivingRoom;
        private BigDecimal areaTotalM2;
        private BigDecimal areaLivingM2;
        private BigDecimal areaKitchenM2;
        private BigDecimal landAreaM2;
        private Integer floor;
        private String floorKind;
        private Integer floorsTotal;
        private Integer bathrooms;
        private Integer balconies;
        private BigDecimal ceilingHeightM;
        private String housingStock;
        private String buildingMaterial;
        private String buildingSeries;
        private String condition;
        private String developer;
        private String parking;
        private String region;
        private String locality;
        private String district;
        private Integer regionCode;
        private Integer localityCode;
        private Integer districtCode;
        private String streetRaw;
        private String houseNumberRaw;
        private String streetType;
        private String streetName;
        private String streetMatch;
        private String areaName;
        private String houseNumber;
        private GeoPoint point;
        private GeoPoint sellerPin;
        private Integer pinRadiusM;
        private String locationSource;
        private String locationPrecision;
        private List<byte[]> phoneHashes = List.of();
        private List<String> photoKeys = List.of();
        private List<String> photoUrls = List.of();
        private boolean hasVideo;
        private List<String> amenities = List.of();
        private List<String> crosslinks = List.of();
        private List<Issue> issues = List.of();

        private Builder() {
        }

        public Builder propertyType(String propertyType) {
            this.propertyType = propertyType;
            return this;
        }

        public Builder sellerType(String sellerType) {
            this.sellerType = sellerType;
            return this;
        }

        public Builder sellerName(String sellerName) {
            this.sellerName = sellerName;
            return this;
        }

        public Builder sellerAccount(String sellerAccount) {
            this.sellerAccount = sellerAccount;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder descriptionRo(String descriptionRo) {
            this.descriptionRo = descriptionRo;
            return this;
        }

        public Builder descriptionRu(String descriptionRu) {
            this.descriptionRu = descriptionRu;
            return this;
        }

        public Builder priceAmount(BigDecimal priceAmount) {
            this.priceAmount = priceAmount;
            return this;
        }

        public Builder priceCurrency(String priceCurrency) {
            this.priceCurrency = priceCurrency;
            return this;
        }

        public Builder priceEur(BigDecimal priceEur) {
            this.priceEur = priceEur;
            return this;
        }

        public Builder pricePerM2Eur(BigDecimal pricePerM2Eur) {
            this.pricePerM2Eur = pricePerM2Eur;
            return this;
        }

        public Builder priceNegotiable(Boolean priceNegotiable) {
            this.priceNegotiable = priceNegotiable;
            return this;
        }

        public Builder downPayment(BigDecimal downPayment) {
            this.downPayment = downPayment;
            return this;
        }

        public Builder rooms(Integer rooms) {
            this.rooms = rooms;
            return this;
        }

        public Builder roomsOpenEnded(boolean roomsOpenEnded) {
            this.roomsOpenEnded = roomsOpenEnded;
            return this;
        }

        public Builder hasLivingRoom(Boolean hasLivingRoom) {
            this.hasLivingRoom = hasLivingRoom;
            return this;
        }

        public Builder areaTotalM2(BigDecimal areaTotalM2) {
            this.areaTotalM2 = areaTotalM2;
            return this;
        }

        public Builder areaLivingM2(BigDecimal areaLivingM2) {
            this.areaLivingM2 = areaLivingM2;
            return this;
        }

        public Builder areaKitchenM2(BigDecimal areaKitchenM2) {
            this.areaKitchenM2 = areaKitchenM2;
            return this;
        }

        public Builder landAreaM2(BigDecimal landAreaM2) {
            this.landAreaM2 = landAreaM2;
            return this;
        }

        public Builder floor(Integer floor) {
            this.floor = floor;
            return this;
        }

        public Builder floorKind(String floorKind) {
            this.floorKind = floorKind;
            return this;
        }

        public Builder floorsTotal(Integer floorsTotal) {
            this.floorsTotal = floorsTotal;
            return this;
        }

        public Builder bathrooms(Integer bathrooms) {
            this.bathrooms = bathrooms;
            return this;
        }

        public Builder balconies(Integer balconies) {
            this.balconies = balconies;
            return this;
        }

        public Builder ceilingHeightM(BigDecimal ceilingHeightM) {
            this.ceilingHeightM = ceilingHeightM;
            return this;
        }

        public Builder housingStock(String housingStock) {
            this.housingStock = housingStock;
            return this;
        }

        public Builder buildingMaterial(String buildingMaterial) {
            this.buildingMaterial = buildingMaterial;
            return this;
        }

        public Builder buildingSeries(String buildingSeries) {
            this.buildingSeries = buildingSeries;
            return this;
        }

        public Builder condition(String condition) {
            this.condition = condition;
            return this;
        }

        public Builder developer(String developer) {
            this.developer = developer;
            return this;
        }

        public Builder parking(String parking) {
            this.parking = parking;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder locality(String locality) {
            this.locality = locality;
            return this;
        }

        public Builder district(String district) {
            this.district = district;
            return this;
        }

        public Builder regionCode(Integer regionCode) {
            this.regionCode = regionCode;
            return this;
        }

        public Builder localityCode(Integer localityCode) {
            this.localityCode = localityCode;
            return this;
        }

        public Builder districtCode(Integer districtCode) {
            this.districtCode = districtCode;
            return this;
        }

        public Builder streetRaw(String streetRaw) {
            this.streetRaw = streetRaw;
            return this;
        }

        public Builder houseNumberRaw(String houseNumberRaw) {
            this.houseNumberRaw = houseNumberRaw;
            return this;
        }

        public Builder streetType(String streetType) {
            this.streetType = streetType;
            return this;
        }

        public Builder streetName(String streetName) {
            this.streetName = streetName;
            return this;
        }

        public Builder streetMatch(String streetMatch) {
            this.streetMatch = streetMatch;
            return this;
        }

        public Builder areaName(String areaName) {
            this.areaName = areaName;
            return this;
        }

        public Builder houseNumber(String houseNumber) {
            this.houseNumber = houseNumber;
            return this;
        }

        public Builder point(GeoPoint point) {
            this.point = point;
            return this;
        }

        public Builder pinRadiusM(Integer pinRadiusM) {
            this.pinRadiusM = pinRadiusM;
            return this;
        }

        public Builder sellerPin(GeoPoint sellerPin) {
            this.sellerPin = sellerPin;
            return this;
        }

        public Builder locationSource(String locationSource) {
            this.locationSource = locationSource;
            return this;
        }

        public Builder locationPrecision(String locationPrecision) {
            this.locationPrecision = locationPrecision;
            return this;
        }

        public Builder phoneHashes(List<byte[]> phoneHashes) {
            this.phoneHashes = phoneHashes;
            return this;
        }

        public Builder photoKeys(List<String> photoKeys) {
            this.photoKeys = photoKeys;
            return this;
        }

        public Builder photoUrls(List<String> photoUrls) {
            this.photoUrls = photoUrls;
            return this;
        }

        public Builder hasVideo(boolean hasVideo) {
            this.hasVideo = hasVideo;
            return this;
        }

        public Builder amenities(List<String> amenities) {
            this.amenities = amenities;
            return this;
        }

        public Builder crosslinks(List<String> crosslinks) {
            this.crosslinks = crosslinks;
            return this;
        }

        public Builder issues(List<Issue> issues) {
            this.issues = issues;
            return this;
        }

        public NormalizedListing build() {
            return new NormalizedListing(propertyType, sellerType, sellerName, sellerAccount, title, descriptionRo, descriptionRu, priceAmount, priceCurrency, priceEur, pricePerM2Eur, priceNegotiable, downPayment, rooms, roomsOpenEnded, hasLivingRoom, areaTotalM2, areaLivingM2, areaKitchenM2, landAreaM2, floor, floorKind, floorsTotal, bathrooms, balconies, ceilingHeightM, housingStock, buildingMaterial, buildingSeries, condition, developer, parking, region, locality, district, regionCode, localityCode, districtCode, streetRaw, houseNumberRaw, streetType, streetName, streetMatch, areaName, houseNumber, point, sellerPin, pinRadiusM, locationSource, locationPrecision, phoneHashes, photoKeys, photoUrls, hasVideo, amenities, crosslinks, issues);
        }
    }
}
