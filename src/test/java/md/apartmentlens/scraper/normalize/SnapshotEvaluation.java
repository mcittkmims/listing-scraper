package md.apartmentlens.scraper.normalize;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import md.apartmentlens.scraper.normalize.address.AddressNormalizer;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.source.RawAd;
import md.apartmentlens.scraper.source.SourceAd;
import md.apartmentlens.scraper.source.ninetynine.NineNineSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the normalizer over a saved 999.md snapshot (one ad JSON per line) and writes a report.
 * Run with: ./gradlew test --tests '*SnapshotEvaluation' -Dsnapshot=/path/ads.jsonl -Dreport=/path/report.txt
 */
@EnabledIfSystemProperty(named = "snapshot", matches = ".+")
class SnapshotEvaluation {

    @Test
    void report() throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        NineNineSource source = new NineNineSource(null, json, 0);
        ListingNormalizer normalizer = new ListingNormalizer(
                new PriceNormalizer((currency, date) -> Optional.of(switch (currency) {
                    case "EUR" -> new BigDecimal("20.0999");
                    case "USD" -> new BigDecimal("17.8523");
                    default -> BigDecimal.ONE;
                })),
                PlaceGazetteer.load(), new AddressNormalizer(StreetGazetteer.load()),
                new PhoneNormalizer("evaluation-key-0123456789"));

        List<String> lines = Files.readAllLines(Path.of(System.getProperty("snapshot")), StandardCharsets.UTF_8);
        Map<String, Integer> issueCounts = new TreeMap<>();
        Map<String, List<String>> issueSamples = new TreeMap<>();
        Map<String, Integer> streetMatch = new TreeMap<>();
        Map<String, Integer> precision = new TreeMap<>();
        Map<String, List<String>> matchSamples = new TreeMap<>();
        int n = 0;
        int withHouse = 0;
        Path matchesFile = Path.of(System.getProperty("report", "build/snapshot-report.txt") + ".matches.tsv");
        PrintWriter matches = new PrintWriter(Files.newBufferedWriter(matchesFile, StandardCharsets.UTF_8));
        for (String line : lines) {
            JsonNode node = json.readTree(line);
            ((tools.jackson.databind.node.ObjectNode) node).remove("_sub");
            RawAd raw = new RawAd(node.path("id").asString(), json.writeValueAsBytes(node));
            SourceAd ad = source.parse(raw);
            NormalizedListing l = normalizer.normalize(ad, LocalDate.of(2026, 10, 19));
            n++;
            if (l.houseNumber() != null) {
                withHouse++;
            }
            streetMatch.merge(String.valueOf(l.streetMatch()), 1, Integer::sum);
            precision.merge(String.valueOf(l.locationPrecision()), 1, Integer::sum);
            List<String> samples = matchSamples.computeIfAbsent(String.valueOf(l.streetMatch()), k -> new java.util.ArrayList<>());
            if (samples.size() < 60) {
                samples.add(ad.locality() == null ? "?" : ad.locality().label() + " | " + ad.street() + " | " + ad.houseNumber()
                        + "  ->  " + l.streetType() + " " + l.streetName() + " " + l.houseNumber());
            }
            if (!"exact".equals(l.streetMatch())) {
                matches.println(l.streetMatch() + "\t" + (ad.locality() == null ? "" : ad.locality().label()) + "\t"
                        + ad.street() + "\t" + ad.houseNumber() + "\t" + l.streetType() + "\t" + l.streetName() + "\t"
                        + l.houseNumber() + "\t" + l.locationPrecision());
            }
            for (Issue i : l.issues()) {
                String key = i.field() + ":" + i.reason();
                issueCounts.merge(key, 1, Integer::sum);
                List<String> s = issueSamples.computeIfAbsent(key, k -> new java.util.ArrayList<>());
                if (s.size() < 25) {
                    s.add(ad.externalId() + " " + i.raw());
                }
            }
        }
        matches.close();
        Path out = Path.of(System.getProperty("report", "build/snapshot-report.txt"));
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.printf("ads %d, with house number %d (%.1f%%)%n", n, withHouse, 100.0 * withHouse / n);
            w.println("street match " + streetMatch);
            w.println("location precision " + precision);
            w.println();
            issueCounts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> w.printf("%6d  %s%n", e.getValue(), e.getKey()));
            for (var e : issueSamples.entrySet()) {
                w.println();
                w.println("== " + e.getKey());
                e.getValue().forEach(s -> w.println("   " + s));
            }
            for (var e : matchSamples.entrySet()) {
                w.println();
                w.println("## street " + e.getKey());
                e.getValue().forEach(s -> w.println("   " + s));
            }
        }
        System.out.println("report written to " + out.toAbsolutePath());
    }

    @SuppressWarnings("unused")
    private static <T> Map<T, Long> count(List<T> list) {
        return list.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
