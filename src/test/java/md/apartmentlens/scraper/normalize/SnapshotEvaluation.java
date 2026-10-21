package md.apartmentlens.scraper.normalize;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import md.apartmentlens.scraper.Fixtures;
import md.apartmentlens.scraper.normalize.address.DefaultPins;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.ninetynine.NineNineSource;
import md.apartmentlens.scraper.text.Text;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the normalizer over a saved 999.md snapshot (one ad JSON per line) and writes a report.
 *
 * <p>Street matches are checked against the map's buildings: for an ad with the seller's own pin, the
 * matched street should have a building within 60 m of the pin. "disagrees" counts suspected wrong
 * matches (or pins on a corner or off target); "no buildings" means the map has none near the pin.
 *
 * <p>Run with: ./gradlew test --tests '*SnapshotEvaluation' -Dsnapshot=/path/ads.jsonl -Dreport=/path/report.txt
 */
@EnabledIfSystemProperty(named = "snapshot", matches = ".+")
class SnapshotEvaluation {

    @Test
    void report() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        NineNineSource source = new NineNineSource(null, json, 0);
        PriceNormalizer prices = new PriceNormalizer((currency, date) -> Optional.of(switch (currency) {
            case "EUR" -> new BigDecimal("20.0999");
            case "USD" -> new BigDecimal("17.8523");
            default -> BigDecimal.ONE;
        }));
        PlaceGazetteer places = Fixtures.places();
        StreetGazetteer streets = Fixtures.streets();
        PhoneNormalizer phones = new PhoneNormalizer("evaluation-key-0123456789");
        LocalDate day = LocalDate.of(2026, 10, 19);

        List<SourceAd> ads = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(System.getProperty("snapshot")), StandardCharsets.UTF_8)) {
            JsonNode node = json.readTree(line);
            ((ObjectNode) node).remove("_sub");
            ads.add(source.parse(new RawAd(node.path("id").asString(), json.writeValueAsBytes(node))));
        }

        // Default pins, found the way DefaultPinRegistry finds them in the database.
        ListingNormalizer first = new ListingNormalizer(prices, places, streets, phones, DefaultPins.NONE);
        Map<Long, Map<String, Integer>> streetsByPin = new HashMap<>();
        for (SourceAd ad : ads) {
            if (ad.pin() != null) {
                NormalizedListing l0 = first.normalize(ad, day);
                String street = "from_pin".equals(l0.streetMatch()) ? null : l0.streetName();
                streetsByPin.computeIfAbsent(DefaultPins.key(ad.pin()), k -> new HashMap<>())
                        .merge(String.valueOf(street), 1, Integer::sum);
            }
        }
        Set<Long> defaults = new HashSet<>();
        streetsByPin.forEach((pin, counts) -> {
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            long named = counts.keySet().stream().filter(k -> !k.equals("null")).count();
            int top = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            if (total >= 5 && named >= 3 && top * 2 <= total) {
                defaults.add(pin);
            }
        });
        ListingNormalizer normalizer = new ListingNormalizer(prices, places, streets, phones,
                pin -> defaults.contains(DefaultPins.key(pin)));

        Map<String, Integer> issueCounts = new TreeMap<>();
        Map<String, List<String>> issueSamples = new TreeMap<>();
        Map<String, Integer> streetMatch = new TreeMap<>();
        Map<String, Integer> location = new TreeMap<>();
        Map<String, Integer> check = new TreeMap<>();
        Map<String, List<String>> disagreements = new TreeMap<>();
        int withHouse = 0;
        Path out = Path.of(System.getProperty("report", "target/snapshot-report.txt"));
        try (PrintWriter matches = new PrintWriter(Files.newBufferedWriter(Path.of(out + ".matches.tsv"), StandardCharsets.UTF_8))) {
            for (SourceAd ad : ads) {
                NormalizedListing l = normalizer.normalize(ad, day);
                if (l.houseNumber() != null) {
                    withHouse++;
                }
                streetMatch.merge(String.valueOf(l.streetMatch()), 1, Integer::sum);
                location.merge(l.locationSource() + "/" + l.locationPrecision(), 1, Integer::sum);
                if (!"exact".equals(l.streetMatch())) {
                    matches.println(l.streetMatch() + "\t" + (ad.locality() == null ? "" : ad.locality().label()) + "\t"
                            + ad.street() + "\t" + ad.houseNumber() + "\t" + l.streetType() + "\t" + l.streetName() + "\t"
                            + l.houseNumber() + "\t" + l.locationSource() + "/" + l.locationPrecision() + "\t" + ad.url());
                }
                boolean ownPin = ad.pin() != null && "pin".equals(l.locationSource());
                if (ownPin && l.streetName() != null && !"unresolved".equals(l.streetMatch())
                        && !"from_pin".equals(l.streetMatch())) {
                    List<StreetGazetteer.Street> around = streets.streetsAround(ad.pin(), 0.06);
                    String key = l.streetMatch() + " ";
                    if (around.isEmpty()) {
                        key += "no buildings";
                    } else if (around.stream().anyMatch(s -> Text.fold(s.name()).equals(Text.fold(l.streetName())))) {
                        key += "agrees";
                    } else {
                        key += "disagrees";
                        List<String> d = disagreements.computeIfAbsent(l.streetMatch(), k -> new ArrayList<>());
                        if (d.size() < 40) {
                            d.add(ad.url() + "  '" + ad.street() + "' -> " + l.streetName() + "   around: "
                                    + around.stream().limit(3).map(StreetGazetteer.Street::name).toList());
                        }
                    }
                    check.merge(key, 1, Integer::sum);
                }
                if (ad.pin() != null && "address".equals(l.locationSource()) && !defaults.contains(DefaultPins.key(ad.pin()))) {
                    boolean onStreet = streets.streetsAround(ad.pin(), 0.06).stream()
                            .anyMatch(s -> Text.fold(s.name()).equals(Text.fold(l.streetName())));
                    double km = ad.pin().kmTo(l.point());
                    check.merge("pin far from address: pin " + (onStreet ? "on the street" : "elsewhere") + ", "
                            + (km < 0.6 ? "300-600 m" : km < 1.5 ? "0.6-1.5 km" : "over 1.5 km"), 1, Integer::sum);
                }
                for (Issue i : l.issues()) {
                    String key = i.field() + ":" + i.reason();
                    issueCounts.merge(key, 1, Integer::sum);
                    List<String> s = issueSamples.computeIfAbsent(key, k -> new ArrayList<>());
                    if (s.size() < 25) {
                        s.add(ad.url() + " " + i.raw());
                    }
                }
            }
        }
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            int n = ads.size();
            w.printf("ads %d, with house number %d (%.1f%%), default pins %d%n", n, withHouse, 100.0 * withHouse / n,
                    defaults.size());
            w.println("street match " + streetMatch);
            w.println("location " + location);
            w.println();
            w.println("street matches checked against buildings within 60 m of the seller's pin:");
            check.forEach((k, v) -> w.printf("%6d  %s%n", v, k));
            w.println();
            issueCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> w.printf("%6d  %s%n", e.getValue(), e.getKey()));
            for (var e : disagreements.entrySet()) {
                w.println();
                w.println("## disagrees: " + e.getKey());
                e.getValue().forEach(s -> w.println("   " + s));
            }
            for (var e : issueSamples.entrySet()) {
                w.println();
                w.println("== " + e.getKey());
                e.getValue().forEach(s -> w.println("   " + s));
            }
        }
        System.out.println("report written to " + out.toAbsolutePath());
    }
}
