package md.apartmentlens.scraper.normalize;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.text.Text;

/**
 * Number of rooms: "Apartament cu 2 camere", "2 camere", "2-комнатная", "двухкомнатная", "garsonieră",
 * "Apartament cu 5 camere sau mai multe". Also the living-room flag: "Apartament cu living".
 */
public final class RoomsNormalizer {

    public record Rooms(Integer count, boolean openEnded, boolean singleRoomOffer) {

        static final Rooms NONE = new Rooms(null, false, false);
    }

    private static final Pattern DIGITS = Pattern.compile("(\\d{1,2})");
    private static final Pattern OPEN_ENDED = Pattern.compile("sau mai mult|si mai mult|и более|или более");
    /** "5+" at the end; "2+1" (two rooms and a living room) is not open-ended. */
    private static final Pattern PLUS = Pattern.compile("\\d\\s*\\+\\s*$");
    private static final Pattern STUDIO = Pattern.compile("garsonier|гостинк|студи|studio");
    /** "O cameră" on its own: a room in a shared flat or a dormitory, not a one-room flat. */
    private static final Pattern SINGLE_ROOM = Pattern.compile("^(o camera|camera|комната|одна комната)$");
    private static final Map<String, Integer> RU_WORDS = Map.of(
            "одно", 1, "одна", 1, "двух", 2, "трех", 3, "четырех", 4, "пяти", 5, "шести", 6);
    private static final Map<String, Integer> RO_WORDS = Map.of(
            "o", 1, "una", 1, "doua", 2, "doi", 2, "trei", 3, "patru", 4, "cinci", 5, "sase", 6);

    public Rooms rooms(String label, Issues issues) {
        String clean = Text.clean(label);
        if (clean == null) {
            return Rooms.NONE;
        }
        String key = Text.fold(clean);
        if (SINGLE_ROOM.matcher(key).matches()) {
            return new Rooms(1, false, true);
        }
        boolean openEnded = OPEN_ENDED.matcher(key).find() || PLUS.matcher(clean).find();
        Matcher m = DIGITS.matcher(key);
        Integer count = null;
        if (m.find()) {
            count = Integer.parseInt(m.group(1));
        } else if (STUDIO.matcher(key).find()) {
            count = 1;
        } else {
            count = wordCount(key);
        }
        if (count == null) {
            issues.add("rooms", clean, "unparsed");
            return Rooms.NONE;
        }
        if (count < 1 || count > 50) {
            issues.add("rooms", clean, "implausible");
            return Rooms.NONE;
        }
        return new Rooms(count, openEnded, false);
    }

    private static Integer wordCount(String key) {
        for (Map.Entry<String, Integer> e : RU_WORDS.entrySet()) {
            if (key.startsWith(e.getKey() + "комнат") || key.contains(" " + e.getKey() + "комнат")) {
                return e.getValue();
            }
        }
        for (String token : key.split(" ")) {
            Integer n = RO_WORDS.get(token);
            if (n != null && (key.contains("camer") || key.contains("odai") || key.contains("odaie"))) {
                return n;
            }
        }
        return null;
    }

    /** "Apartament cu living" -> true, "Apartament fără living" -> false. */
    public Boolean livingRoom(String label, Issues issues) {
        String clean = Text.clean(label);
        if (clean == null) {
            return null;
        }
        String key = Text.fold(clean);
        if (key.contains("fara living") || key.contains("без гостин") || key.contains("без зала")) {
            return Boolean.FALSE;
        }
        if (key.contains("cu living") || key.contains("с гостин") || key.contains("с залом") || key.equals("living")) {
            return Boolean.TRUE;
        }
        issues.add("has_living_room", clean, "unknown_label");
        return null;
    }
}
