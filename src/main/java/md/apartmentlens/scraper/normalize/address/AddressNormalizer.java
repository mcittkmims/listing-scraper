package md.apartmentlens.scraper.normalize.address;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.normalize.Issues;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer.Place;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer.Match;
import md.apartmentlens.scraper.source.GeoPoint;
import md.apartmentlens.scraper.text.Text;

/**
 * Street and house number: one official spelling of the street (Romanian, from OpenStreetMap) and a
 * clean house number. Russian spellings ("ул. Каля Ешилор"), old names, missing type words, typos and
 * house numbers typed into the street field ("Mihail Lomonosov, 39") are handled; anything uncertain
 * stays as the seller wrote it and is marked unresolved.
 */
public final class AddressNormalizer {

    /** A pin this close to the street confirms it; farther, the street is searched around the locality. */
    static final double PIN_RADIUS_KM = 1.5;

    private static final Pattern TRAILING_NUMBER = Pattern.compile(
            "^(.*?\\p{L}.*?)(\\s*,\\s*|\\s+)(?:nr\\.?\\s*)?(\\d{1,3}(?:\\s*/\\s*\\d{1,3})?\\s*[A-Za-z]?)$");

    /**
     * @param street   the recognized street with its position, when known
     * @param building the map's position of this house number on that street, when the map has it
     */
    public record Address(String streetType, String streetName, String streetMatch, String houseNumber,
                          StreetGazetteer.Street street, GeoPoint building, String areaName) {

        static final Address NONE = new Address(null, null, null, null, null, null, null);

        Address(String streetType, String streetName, String streetMatch, String houseNumber,
                StreetGazetteer.Street street, GeoPoint building) {
            this(streetType, streetName, streetMatch, houseNumber, street, building, null);
        }

        Address withArea(String area) {
            return new Address(streetType, streetName, streetMatch, houseNumber, street, building, area);
        }
    }

    /**
     * Words that name an area, not a street: "Cartierul Cluj", "Satul German", "ЖК Grand Park", garden
     * associations ("IP Fintinilor", "Cooperativa Botna") and "centru".
     */
    private static final Pattern AREA = Pattern.compile(
            "^(cartier(ul)?|complex(ul)?( locativ| rezidential)?|satul|sat|zona|microraion(ul)?|ansamblu(l)?( rezidential)?"
                    + "|ip|intovarasirea( pomicola)?|cooperativa|asociatia|centru"
                    + "|жк|жилой комплекс|микрорайон|квартал|коттеджный городок|садовое товарищество|снт|с т|residence|residential)"
                    + "( |$)");

    private final StreetGazetteer streets;
    private final PlaceGazetteer places;
    private final Map<Integer, Optional<Place>> owners = new ConcurrentHashMap<>();

    public AddressNormalizer(StreetGazetteer streets, PlaceGazetteer places) {
        this.streets = streets;
        this.places = places;
    }

    /**
     * True unless the street clearly belongs to another settlement inside the locality's search area: its
     * buildings' addresses name another town, or (with no addresses) it lies away from the locality's core
     * and well inside a neighbouring village.
     */
    private boolean inLocality(StreetGazetteer.Street street, Place locality) {
        if (!street.city().isEmpty()) {
            String city = PlaceGazetteer.placeKey(street.city());
            return city.equals(Text.fold(locality.nameRo())) || city.equals(Text.fold(locality.nameRu()));
        }
        double fromLocality = Math.max(0, street.point().kmTo(locality.point()) - street.extentKm());
        if (fromLocality <= 0.6 * locality.radiusKm()) {
            return true;
        }
        return owners.computeIfAbsent(street.index(), i -> places.ownerOf(street.point()))
                .map(owner -> owner.equals(locality) || owner.point().kmTo(street.point()) > owner.radiusKm())
                .orElse(true);
    }

    public StreetGazetteer streets() {
        return streets;
    }

    /** True when the locality itself has a street of exactly this name (in any spelling the map knows). */
    public boolean isStreetIn(String name, Place locality) {
        return streets.resolve(name, null, locality.point(), locality.radiusKm(), false, s -> inLocality(s, locality))
                .filter(m -> m.method().equals("exact") || m.method().equals("alias"))
                .isPresent();
    }

    /**
     * @param pin      the seller's pin, already checked to lie in the locality, or null
     * @param locality the ad's settlement, or null when unknown
     */
    public Address normalize(String streetRaw, String houseRaw, Place locality, GeoPoint pin, Issues issues) {
        HouseNumbers.Result house = HouseNumbers.parse(houseRaw);
        String street = Text.clean(streetRaw);
        if (street != null && !street.matches(".*\\p{L}.*")) {
            issues.add("street", street, "hidden");
            street = null;
        }
        if (street == null) {
            return fromPin(null, houseValue(house, houseRaw, issues), pin);
        }
        street = mainPart(street, locality, pin);
        if (isNoName(street)) {
            // "нет", "Nui denumirea", "hgfjkl", "V": the seller gave no street.
            return fromPin(null, houseValue(house, houseRaw, issues), pin);
        }
        street = dropLeadingLocality(street, locality);
        String area = areaName(street, locality);
        if (area != null) {
            // Not a street: keep it as the area, and take the street from the seller's pin if it shows one.
            return fromPin(null, houseValue(house, houseRaw, issues), pin).withArea(area);
        }

        // A number typed after the street name: used when the house field gives none, dropped when it
        // repeats the house number. Never cut from a name that ends in a number ("31 August 1989").
        Matcher tail = TRAILING_NUMBER.matcher(street);
        if (tail.matches()) {
            String namePart = tail.group(1).strip();
            HouseNumbers.Result fromStreet = HouseNumbers.parse(tail.group(3));
            boolean nameAlone = tail.group(2).contains(",")
                    || (resolve(namePart, locality, pin).isPresent() && !resolvesFully(street, locality, pin));
            if (nameAlone && fromStreet.kind() == HouseNumbers.Kind.NUMBER) {
                if (house.kind() != HouseNumbers.Kind.NUMBER) {
                    house = fromStreet;
                    street = namePart;
                } else if (sameNumber(house.value(), fromStreet.value())) {
                    street = namePart;
                }
            }
        }

        String houseNumber = houseValue(house, houseRaw, issues);
        StreetTypes.Split split = StreetTypes.split(street);
        if (split.name() == null) {
            if (split.type() == null) {
                issues.add("street", streetRaw, "unparsed");
            }
            return fromPin(null, houseNumber, pin); // only a type word, "str": no street given
        }
        if (namesLocality(split.name(), locality)) {
            // "Trușeni" or "s. Trușeni" as the street of an ad in Trușeni: the seller gave no street.
            return fromPin(null, houseNumber, pin);
        }
        Optional<Match> match = resolve(split.name(), split.type(), locality, pin);
        if (match.isEmpty()) {
            if (locality != null) {
                issues.add("street", streetRaw, "unresolved");
            }
            // The seller named a street: the pin's street is used only if it looks like what they wrote
            // ("ул. Буковина" on Strada Bucovina). A different street at the pin is not put in its place:
            // theirs may be missing from the map.
            Address atPin = fromPin(null, houseNumber, pin);
            if (atPin.street() != null && streets.likeness(split.name(), atPin.street()) >= StreetGazetteer.NEARBY_MIN) {
                return new Address(atPin.streetType(), atPin.streetName(), "nearby", houseNumber, atPin.street(),
                        atPin.building());
            }
            return new Address(split.type(), split.name(), "unresolved", houseNumber, null, null);
        }
        Match m = match.get();
        GeoPoint building = streets.address(m.street(), houseNumber).orElse(null);
        return new Address(m.type() != null ? m.type() : split.type(), m.name(), m.method(), houseNumber, m.street(),
                building);
    }

    /**
     * The part of the street field that names the street. Sellers repeat it ("str. X;str. X"), add the
     * district and city ("str. Vasile Lupu, Buiucani, Chișinău") or a corner ("Ciocana, bd. X, 47").
     * The first comma-separated part that is a known street wins; otherwise the place names are dropped.
     */
    String mainPart(String street, Place locality, GeoPoint pin) {
        String s = street.split(";")[0].strip();
        if (!s.contains(",")) {
            return s;
        }
        String[] parts = s.split(",");
        for (String part : parts) {
            if (part.matches(".*\\p{L}.*") && resolve(part.strip(), locality, pin).isPresent()) {
                // Keep a number written right after it: "bd. Dacia, 47".
                int i = s.indexOf(part) + part.length();
                String rest = s.substring(i).replaceFirst("^\\s*,\\s*", "");
                return rest.matches("\\d{1,3}[A-Za-z]?(\\s*/\\s*\\d{1,3})?") ? part.strip() + ", " + rest : part.strip();
            }
        }
        List<String> kept = new ArrayList<>();
        for (String part : parts) {
            String key = PlaceGazetteer.placeKey(part);
            if (key.isEmpty() || (locality != null && (key.equals(Text.fold(locality.nameRo()))
                    || key.equals(Text.fold(locality.nameRu()))))) {
                continue;
            }
            kept.add(part.strip());
        }
        return kept.isEmpty() ? s : String.join(", ", kept);
    }

    /**
     * The street the seller's own pin stands on, when the seller gave no street ("-", ".", "str", the
     * village name). Marked {@code from_pin}: it comes from the pin, not from what the seller wrote.
     */
    private Address fromPin(String type, String houseNumber, GeoPoint pin) {
        Optional<StreetGazetteer.Street> at = pin == null ? Optional.empty() : streets.streetAt(pin);
        if (at.isEmpty()) {
            return new Address(type, null, null, houseNumber, null, null);
        }
        StreetGazetteer.Street st = at.get();
        return new Address(st.type(), st.name(), "from_pin", houseNumber, st, streets.address(st, houseNumber).orElse(null));
    }

    private Optional<Match> resolve(String street, Place locality, GeoPoint pin) {
        StreetTypes.Split split = StreetTypes.split(street);
        return split.name() == null ? Optional.empty() : resolve(split.name(), split.type(), locality, pin);
    }

    /** True when the whole text is itself a street name (not just similar to one). */
    private boolean resolvesFully(String street, Place locality, GeoPoint pin) {
        return resolve(street, locality, pin).map(m -> m.method().equals("exact") || m.method().equals("alias"))
                .orElse(false);
    }

    private Optional<Match> resolve(String name, String type, Place locality, GeoPoint pin) {
        Optional<Match> found = resolveOnce(name, type, locality, pin);
        if (found.isEmpty()) {
            // A stray short word in front: "St Ialoveni", "- L. Deleanu".
            String[] words = name.strip().split("[\\s.]+", 2);
            if (words.length == 2 && words[0].matches("[\\p{L}\\-]{1,2}") && Text.fold(words[1]).length() >= 4) {
                found = resolveOnce(words[1], type, locality, pin);
            }
        }
        return found;
    }

    private Optional<Match> resolveOnce(String name, String type, Place locality, GeoPoint pin) {
        if (pin != null) {
            Optional<Match> near = streets.resolve(name, type, pin, PIN_RADIUS_KM, true);
            if (near.isEmpty()) {
                near = streets.resolveAtPin(name, type, pin);
            }
            if (near.isPresent()) {
                return near;
            }
        }
        if (locality == null) {
            return Optional.empty();
        }
        // Without the seller's pin, the ad's own locality first: an ad in Chișinău gets Chișinău's Strada Mihail
        // Sadoveanu, not Cricova's. Only when the locality has no such street, the surrounding area: sellers
        // list streets of Durlești or Codru on the city's edge as "Chișinău".
        Optional<Match> found = streets.resolve(name, type, locality.point(), locality.radiusKm(), false,
                s -> inLocality(s, locality));
        if (found.isEmpty()) {
            found = streets.resolve(name, type, locality.point(), locality.radiusKm(), false);
        }
        // An approximate match far from the seller's pin is more likely a different street.
        if (found.isPresent() && pin != null && found.get().street() != null
                && !found.get().method().equals("exact") && !found.get().method().equals("alias")
                && streets.distanceKm(found.get().street(), pin) > 2 * PIN_RADIUS_KM) {
            return Optional.empty();
        }
        return found;
    }

    static boolean namesLocality(String name, Place locality) {
        if (locality == null) {
            return false;
        }
        String key = PlaceGazetteer.placeKey(name);
        return key.equals(Text.fold(locality.nameRo())) || (!locality.nameRu().isEmpty() && key.equals(Text.fold(locality.nameRu())));
    }

    /**
     * The area named instead of a street, or null: words like "cartierul" or "ЖК", or the name of a
     * district or quarter of the locality ("Poiana Domnească").
     */
    String areaName(String street, Place locality) {
        String key = Text.fold(street);
        if (AREA.matcher(key).find()) {
            return street;
        }
        StreetTypes.Split split = StreetTypes.split(street);
        if (split.type() == null && split.name() != null && locality != null
                && resolve(split.name(), null, locality, null).isEmpty()) {
            if (places.district(split.name(), locality).isPresent()) {
                return street;
            }
            // Another village nearby written where the street belongs ("Holercani" for an ad in Ustia). Exact
            // names only: "Dumbravei" is a street of Durlești, not the village Dumbrava.
            Optional<Place> other = places.settlementExactly(split.name(), locality.point());
            if (other.isPresent() && !other.get().equals(locality) && other.get().point().kmTo(locality.point()) < 15) {
                return street;
            }
        }
        return null;
    }

    private static final Pattern NO_NAME = Pattern.compile(
            "^(net|нет|nu|nui|nu este|nu are|nui denumirea|nu are denumire|fara denumire|fara nume|без названия|"
                    + "neidentificat|neindentificat|necunoscut|periferie|periferia|-+|\\?+)$");

    /** Text that names no street: a refusal, a placeholder, keyboard mashing or a single letter. */
    static boolean isNoName(String street) {
        StreetTypes.Split split = StreetTypes.split(street);
        String name = split.name() == null ? "" : split.name();
        String key = Text.fold(name);
        if (key.isEmpty()) {
            return false;
        }
        if (NO_NAME.matcher(key).matches()) {
            return true;
        }
        String letters = key.replaceAll("[^\\p{L}]", "");
        if (letters.isEmpty() || key.matches(".*\\d.*")) {
            return false;
        }
        boolean noVowel = !letters.matches(".*[aeiouyăâîаеёиоуыэюя].*");
        boolean oneLetterRepeated = letters.chars().distinct().count() == 1;
        return letters.length() <= 2 || oneLetterRepeated || (noVowel && letters.length() <= 8);
    }

    /** "Ciutesti Tudor tarna" in Ciutești: the street is Tudor Țărnă. */
    static String dropLeadingLocality(String street, Place locality) {
        if (locality == null) {
            return street;
        }
        String key = Text.fold(street);
        for (String name : new String[] {locality.nameRo(), locality.nameRu()}) {
            String n = Text.fold(name);
            if (!n.isEmpty() && key.startsWith(n + " ") && key.length() > n.length() + 3) {
                String[] words = street.strip().split("\\s+");
                int drop = n.split(" ").length;
                return words.length > drop ? String.join(" ", Arrays.copyOfRange(words, drop, words.length)) : street;
            }
        }
        return street;
    }

    /** "124" and "124F" name the same house number in a street field. */
    static boolean sameNumber(String house, String fromStreet) {
        return house.equals(fromStreet) || house.replaceAll("\\D.*$", "").equals(fromStreet.replaceAll("\\D.*$", ""));
    }

    private static String houseValue(HouseNumbers.Result house, String raw, Issues issues) {
        switch (house.kind()) {
            case NUMBER -> {
                return house.value();
            }
            case BLOCK -> issues.add("house_number", raw, "block_not_house");
            case INVALID -> issues.add("house_number", raw, "unparsed");
            case HIDDEN, NONE -> {
                // Not a problem to report: agencies hide numbers on purpose; the raw value is kept.
            }
        }
        return null;
    }
}
