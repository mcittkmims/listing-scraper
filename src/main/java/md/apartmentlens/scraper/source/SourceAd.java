package md.apartmentlens.scraper.source;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * One ad as a source parser read it: raw labels and numbers, before any normalization.
 *
 * @param externalId the ad id at the source (required)
 * @param url public link to the ad (required)
 * @param category the source category the ad was listed in (required)
 * @param offerType offer label, e.g. "Vând"
 * @param sellerType seller label, e.g. "Agenție", "Persoană fizică"
 * @param sellerName agency or company name
 * @param sellerAccount seller account id at the source
 * @param rooms rooms label or number, e.g. "Apartament cu 2 camere", "3"
 * @param livingRoom living-room label, e.g. "Apartament cu living"
 * @param propertyType type label within the category, e.g. "Townhouse"
 * @param floor floor label, e.g. "7", "Demisol", "3/9"
 * @param floorsTotal number of floors label, e.g. "9", "2 etaje"
 * @param housingStock e.g. "Construcții noi", "Secundar"
 * @param buildingMaterial e.g. "Cărămidă", "Panou"
 * @param layout building series or layout, e.g. "143", "Ms (serie moldovenească)"
 * @param condition e.g. "Variantă albă", "Euroreparație"
 * @param street street as typed by the seller
 * @param houseNumber house number as typed by the seller
 * @param pin map point placed by the seller, or by the site near the property
 * @param pinRadiusM null for a pin on the property; for a pin the site placed only near it, how far off it can be
 * @param phones phone numbers as published
 * @param photoKeys source photo ids, in order
 * @param amenities amenity codes, see Amenity
 * @param crosslinks the same ad on other sites, as the source names them: "999md:105689225"
 */
public record SourceAd(
        String externalId,
        String url,
        SourceCategory category,
        String offerType,
        String sellerType,
        String sellerName,
        String sellerAccount,
        String title,
        String descriptionRo,
        String descriptionRu,
        RawPrice price,
        String rooms,
        String livingRoom,
        String propertyType,
        Quantity areaTotal,
        Quantity areaLiving,
        Quantity areaKitchen,
        Quantity landArea,
        Quantity ceilingHeight,
        String floor,
        String floorsTotal,
        String housingStock,
        String buildingMaterial,
        String layout,
        String condition,
        String developer,
        String bathrooms,
        String balconies,
        String parking,
        Coded region,
        Coded locality,
        Coded district,
        String street,
        String houseNumber,
        GeoPoint pin,
        Integer pinRadiusM,
        List<String> phones,
        List<String> photoKeys,
        boolean hasVideo,
        Set<String> amenities,
        List<String> crosslinks,
        Instant postedAt,
        Instant republishedAt) {

    public static Builder builder() {
        return new Builder();
    }

    /** Builder; every field is optional unless documented otherwise. */
    public static final class Builder {

        private String externalId;
        private String url;
        private SourceCategory category;
        private String offerType;
        private String sellerType;
        private String sellerName;
        private String sellerAccount;
        private String title;
        private String descriptionRo;
        private String descriptionRu;
        private RawPrice price;
        private String rooms;
        private String livingRoom;
        private String propertyType;
        private Quantity areaTotal;
        private Quantity areaLiving;
        private Quantity areaKitchen;
        private Quantity landArea;
        private Quantity ceilingHeight;
        private String floor;
        private String floorsTotal;
        private String housingStock;
        private String buildingMaterial;
        private String layout;
        private String condition;
        private String developer;
        private String bathrooms;
        private String balconies;
        private String parking;
        private Coded region;
        private Coded locality;
        private Coded district;
        private String street;
        private String houseNumber;
        private GeoPoint pin;
        private Integer pinRadiusM;
        private List<String> phones = List.of();
        private List<String> photoKeys = List.of();
        private boolean hasVideo;
        private Set<String> amenities = Set.of();
        private List<String> crosslinks = List.of();
        private Instant postedAt;
        private Instant republishedAt;

        private Builder() {
        }

        public Builder externalId(String externalId) {
            this.externalId = externalId;
            return this;
        }

        public Builder url(String url) {
            this.url = url;
            return this;
        }

        public Builder category(SourceCategory category) {
            this.category = category;
            return this;
        }

        public Builder offerType(String offerType) {
            this.offerType = offerType;
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

        public Builder price(RawPrice price) {
            this.price = price;
            return this;
        }

        public Builder rooms(String rooms) {
            this.rooms = rooms;
            return this;
        }

        public Builder livingRoom(String livingRoom) {
            this.livingRoom = livingRoom;
            return this;
        }

        public Builder propertyType(String propertyType) {
            this.propertyType = propertyType;
            return this;
        }

        public Builder areaTotal(Quantity areaTotal) {
            this.areaTotal = areaTotal;
            return this;
        }

        public Builder areaLiving(Quantity areaLiving) {
            this.areaLiving = areaLiving;
            return this;
        }

        public Builder areaKitchen(Quantity areaKitchen) {
            this.areaKitchen = areaKitchen;
            return this;
        }

        public Builder landArea(Quantity landArea) {
            this.landArea = landArea;
            return this;
        }

        public Builder ceilingHeight(Quantity ceilingHeight) {
            this.ceilingHeight = ceilingHeight;
            return this;
        }

        public Builder floor(String floor) {
            this.floor = floor;
            return this;
        }

        public Builder floorsTotal(String floorsTotal) {
            this.floorsTotal = floorsTotal;
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

        public Builder layout(String layout) {
            this.layout = layout;
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

        public Builder bathrooms(String bathrooms) {
            this.bathrooms = bathrooms;
            return this;
        }

        public Builder balconies(String balconies) {
            this.balconies = balconies;
            return this;
        }

        public Builder parking(String parking) {
            this.parking = parking;
            return this;
        }

        public Builder region(Coded region) {
            this.region = region;
            return this;
        }

        public Builder locality(Coded locality) {
            this.locality = locality;
            return this;
        }

        public Builder district(Coded district) {
            this.district = district;
            return this;
        }

        public Builder street(String street) {
            this.street = street;
            return this;
        }

        public Builder houseNumber(String houseNumber) {
            this.houseNumber = houseNumber;
            return this;
        }

        public Builder pin(GeoPoint pin) {
            this.pin = pin;
            return this;
        }

        public Builder pinRadiusM(Integer pinRadiusM) {
            this.pinRadiusM = pinRadiusM;
            return this;
        }

        public Builder phones(List<String> phones) {
            this.phones = phones;
            return this;
        }

        public Builder photoKeys(List<String> photoKeys) {
            this.photoKeys = photoKeys;
            return this;
        }

        public Builder hasVideo(boolean hasVideo) {
            this.hasVideo = hasVideo;
            return this;
        }

        public Builder amenities(Set<String> amenities) {
            this.amenities = amenities;
            return this;
        }

        public Builder crosslinks(List<String> crosslinks) {
            this.crosslinks = crosslinks;
            return this;
        }

        public Builder postedAt(Instant postedAt) {
            this.postedAt = postedAt;
            return this;
        }

        public Builder republishedAt(Instant republishedAt) {
            this.republishedAt = republishedAt;
            return this;
        }

        public SourceAd build() {
            return new SourceAd(externalId, url, category, offerType, sellerType, sellerName, sellerAccount, title, descriptionRo, descriptionRu, price, rooms, livingRoom, propertyType, areaTotal, areaLiving, areaKitchen, landArea, ceilingHeight, floor, floorsTotal, housingStock, buildingMaterial, layout, condition, developer, bathrooms, balconies, parking, region, locality, district, street, houseNumber, pin, pinRadiusM, phones, photoKeys, hasVideo, amenities, crosslinks, postedAt, republishedAt);
        }
    }
}
