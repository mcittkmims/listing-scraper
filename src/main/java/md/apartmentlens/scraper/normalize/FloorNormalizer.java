package md.apartmentlens.scraper.normalize;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.text.Text;

/**
 * Floors: "3", "3/9", "etajul 3 din 9", "3 этаж из 9", "Demisol", "Mansardă", "Penthouse", "2 etaje".
 *
 * <p>Numbering follows Moldovan usage: the first above-ground floor is 1, the semi-basement 0 and the
 * basement -1. An attic or penthouse has no number of its own. A floor above the building's last floor
 * contradicts itself, so the floor is dropped and the conflict reported: a wrong floor would wrongly
 * keep two ads of the same flat apart.
 */
public final class FloorNormalizer {

    public record Floor(Integer floor, String kind, Integer floorsTotal) {
    }

    private static final Pattern OF_TOTAL = Pattern.compile(
            "(-?\\d{1,2})\\s*(?:/|din|из|из\\s+|etaj\\s+din|этаж\\s+из)\\s*(\\d{1,2})");
    private static final Pattern NUMBER = Pattern.compile("(-?\\d{1,2})");

    public Floor floor(String floorLabel, String floorsTotalLabel, Issues issues) {
        String floorText = Text.clean(floorLabel);
        String totalText = Text.clean(floorsTotalLabel);
        Integer floor = null;
        String kind = null;
        Integer total = null;

        if (floorText != null) {
            String key = Text.fold(floorText);
            String lower = floorText.toLowerCase(Locale.ROOT);
            Matcher both = OF_TOTAL.matcher(lower);
            if (both.find()) {
                floor = Integer.parseInt(both.group(1));
                total = Integer.parseInt(both.group(2));
            } else if (key.contains("demisol") || key.contains("полуподвал") || key.contains("цокол")) {
                floor = 0;
                kind = "semi_basement";
            } else if (key.contains("subsol") || key.contains("подвал")) {
                floor = -1;
                kind = "basement";
            } else if (key.contains("mansard") || key.contains("мансард")) {
                kind = "attic";
            } else if (key.contains("penthouse") || key.contains("пентхаус")) {
                kind = "penthouse";
            } else if (key.equals("parter") || key.contains("первый")) {
                floor = 1;
            } else {
                Matcher m = NUMBER.matcher(lower); // not the folded key: it drops the minus sign
                if (m.find()) {
                    floor = Integer.parseInt(m.group(1));
                } else {
                    issues.add("floor", floorText, "unparsed");
                }
            }
        }

        if (totalText != null) {
            Matcher m = NUMBER.matcher(Text.fold(totalText));
            if (m.find()) {
                int t = Integer.parseInt(m.group(1));
                if (total != null && total != t) {
                    issues.add("floors_total", floorText + " | " + totalText, "conflict");
                    total = null;
                } else {
                    total = t;
                }
            } else {
                issues.add("floors_total", totalText, "unparsed");
            }
        }

        if (floor != null && (floor < -3 || floor > 60)) {
            issues.add("floor", floorText, "implausible");
            floor = null;
        }
        if (total != null && (total < 1 || total > 60)) {
            issues.add("floors_total", totalText != null ? totalText : floorText, "implausible");
            total = null;
        }
        if (floor != null && total != null && floor > total) {
            issues.add("floor", floor + "/" + total, "above_last_floor");
            floor = null;
        }
        return new Floor(floor, kind, total);
    }
}
