package md.apartmentlens.scraper.normalize.address;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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

    public record Address(String streetType, String streetName, String streetMatch, String houseNumber,
                          StreetGazetteer.Street street) {

        static final Address NONE = new Address(null, null, null, null, null);
    }

    private final StreetGazetteer streets;

    public AddressNormalizer(StreetGazetteer streets) {
        this.streets = streets;
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
            return new Address(null, null, null, houseValue(house, houseRaw, issues), null);
        }
        street = mainPart(street, locality, pin);

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
            return new Address(null, null, null, houseNumber, null); // only a type word, "str": no street given
        }
        if (namesLocality(split.name(), locality)) {
            // "Trușeni" or "s. Trușeni" as the street of an ad in Trușeni: the seller gave no street.
            return new Address(null, null, null, houseNumber, null);
        }
        Optional<Match> match = resolve(split.name(), split.type(), locality, pin);
        if (match.isEmpty()) {
            if (locality != null) {
                issues.add("street", streetRaw, "unresolved");
            }
            return new Address(split.type(), split.name(), "unresolved", houseNumber, null);
        }
        Match m = match.get();
        return new Address(m.type() != null ? m.type() : split.type(), m.name(), m.method(), houseNumber, m.street());
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
            if (near.isPresent()) {
                return near;
            }
        }
        if (locality == null) {
            return Optional.empty();
        }
        Optional<Match> found = streets.resolve(name, type, locality.point(), locality.radiusKm(), false);
        // An approximate match far from the seller's pin is more likely a different street.
        if (found.isPresent() && pin != null && found.get().street() != null
                && !found.get().method().equals("exact") && !found.get().method().equals("alias")) {
            StreetGazetteer.Street st = found.get().street();
            if (pin.kmTo(st.point()) - st.extentKm() > 2 * PIN_RADIUS_KM) {
                return Optional.empty();
            }
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
