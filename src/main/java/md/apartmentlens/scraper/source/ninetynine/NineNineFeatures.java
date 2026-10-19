package md.apartmentlens.scraper.source.ninetynine;

import java.util.List;
import java.util.Map;

/**
 * 999.md attribute ("feature") ids for real estate, read from the site's category filters and ad pages.
 * Every attribute of an ad is a feature with a numeric id; options carry their own numeric ids.
 */
final class NineNineFeatures {

    static final int OFFER_TYPE = 1;
    static final int PRICE = 2;
    static final int MAP_POINT = 3;
    static final int REGION = 7;
    static final int LOCALITY = 8;
    static final int DISTRICT = 9;
    static final int STREET = 10;
    static final int HOUSE_NUMBER = 11;
    static final int TITLE = 12;
    static final int BODY = 13;
    static final int IMAGES = 14;
    static final int VIDEOS = 15;
    static final int CONTACTS = 16;
    static final int ROOMS_APARTMENT = 241;   // option: "Apartament cu 2 camere"
    static final int ROOMS_HOUSE = 588;       // number
    static final int LIVING_ROOM = 2199;
    static final int AREA_TOTAL = 244;
    static final int AREA_LIVING = 239;
    static final int AREA_KITCHEN = 242;
    static final int AREA_LAND = 245;
    static final int CEILING_HEIGHT = 237;
    static final int FLOOR = 248;
    static final int FLOORS_TOTAL = 249;
    static final int HOUSING_STOCK = 852;
    static final int DEVELOPER = 1658;
    static final int BUILDING_MATERIAL = 247;
    static final int APARTMENT_CONDITION = 253;
    static final int HOUSE_CONDITION = 254;
    static final int LAYOUT = 246;
    static final int BATHROOMS = 252;
    static final int BALCONIES = 250;
    static final int PARKING = 251;
    static final int HOUSE_TYPE = 1311;
    static final int SELLER_TYPE = 795;
    static final int COMPANY = 2563;
    static final int UPLOADED_VIDEO = 2562;
    static final int PLUMBING = 1622;
    static final int SEWERAGE = 1623;
    static final int GAS = 1624;

    /** Yes/no features -> amenity code. */
    static final Map<Integer, String> AMENITIES = Map.ofEntries(
            Map.entry(154, "autonomous_heating"),
            Map.entry(158, "pool"),
            Map.entry(160, "armored_door"),
            Map.entry(162, "video_surveillance"),
            Map.entry(166, "remote_gate"),
            Map.entry(170, "garage"),
            Map.entry(171, "move_in_ready"),
            Map.entry(172, "move_in_ready"),
            Map.entry(173, "playground"),
            Map.entry(175, "intercom"),
            Map.entry(176, "annex"),
            Map.entry(179, "internet"),
            Map.entry(180, "fireplace"),
            Map.entry(182, "air_conditioning"),
            Map.entry(183, "air_conditioning"),
            Map.entry(186, "elevator"),
            Map.entry(187, "furnished"),
            Map.entry(188, "furnished"),
            Map.entry(193, "separate_entrance"),
            Map.entry(196, "parquet"),
            Map.entry(197, "recreation_area"),
            Map.entry(198, "recreation_area"),
            Map.entry(200, "cellar"),
            Map.entry(203, "staff_room"),
            Map.entry(211, "near_water"),
            Map.entry(214, "appliances"),
            Map.entry(215, "garden"),
            Map.entry(216, "sauna"),
            Map.entry(217, "alarm"),
            Map.entry(218, "smart_home"),
            Map.entry(220, "water_purification"),
            Map.entry(223, "double_glazing"),
            Map.entry(225, "cable_tv"),
            Map.entry(226, "phone_line"),
            Map.entry(227, "phone_line"),
            Map.entry(229, "floor_heating"),
            Map.entry(230, "terrace"),
            Map.entry(232, "part_of_house"),
            Map.entry(235, "generator"),
            Map.entry(1759, "panoramic_windows"),
            Map.entry(1994, "laminate"),
            Map.entry(2504, "intercom"),
            Map.entry(2561, "dishwasher"));

    /** Utility options meaning "connected". */
    static final Map<Integer, String> UTILITY_OPTIONS = Map.of(
            27757, "plumbing",
            27759, "sewerage",
            27761, "gas");

    /** Every feature requested for each ad. */
    static final List<Integer> ALL = List.of(
            OFFER_TYPE, PRICE, MAP_POINT, REGION, LOCALITY, DISTRICT, STREET, HOUSE_NUMBER, TITLE, BODY, IMAGES,
            VIDEOS, CONTACTS, ROOMS_APARTMENT, ROOMS_HOUSE, LIVING_ROOM, AREA_TOTAL, AREA_LIVING, AREA_KITCHEN,
            AREA_LAND, CEILING_HEIGHT, FLOOR, FLOORS_TOTAL, HOUSING_STOCK, DEVELOPER, BUILDING_MATERIAL,
            APARTMENT_CONDITION, HOUSE_CONDITION, LAYOUT, BATHROOMS, BALCONIES, PARKING, HOUSE_TYPE, SELLER_TYPE,
            COMPANY, UPLOADED_VIDEO, PLUMBING, SEWERAGE, GAS,
            154, 158, 160, 162, 166, 170, 171, 172, 173, 175, 176, 179, 180, 182, 183, 186, 187, 188, 193, 196,
            197, 198, 200, 203, 211, 214, 215, 216, 217, 218, 220, 223, 225, 226, 227, 229, 230, 232, 235, 1759,
            1994, 2504, 2561);

    private NineNineFeatures() {
    }
}
