package md.apartmentlens.scraper.ref;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import md.apartmentlens.scraper.http.PoliteHttpClient;
import md.apartmentlens.scraper.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the cadastre's public layers from geodata.gov.md: commune and town boundaries (UAT-1), street
 * lines (Strazi_RM) and the official address points (adresa_point). Downloads page by page, then
 * replaces the cadastre rows of the {@code ref} tables in one transaction.
 */
public class CadastreImporter {

    private static final Logger log = LoggerFactory.getLogger(CadastreImporter.class);
    static final String SOURCE = "cadastre";
    static final String WFS = "https://geodata.gov.md/geoserver/cadastru_data/ows?service=WFS&version=2.0.0"
            + "&request=GetFeature&outputFormat=application/json&srsName=EPSG:4326";
    static final int PAGE = 10_000;

    private final PoliteHttpClient http;
    private final JsonMapper json;
    private final RefStore store;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CadastreImporter(PoliteHttpClient http, JsonMapper json, RefStore store, TransactionTemplate tx, Clock clock) {
        this.http = http;
        this.json = json;
        this.store = store;
        this.tx = tx;
        this.clock = clock;
    }

    public Map<String, Integer> run() {
        tx.executeWithoutResult(s -> {
            try {
                store();
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e); // rolls back: the previous import stays
            }
        });
        Map<String, Integer> counts = store.counts(SOURCE);
        log.info("cadastre imported: {}", counts);
        return counts;
    }

    /** Downloads and stores page by page, so only one page is in memory at a time. */
    private void store() throws IOException {
        store.clear(SOURCE);
        download("UAT1", f -> {
            String fullName = text(f, "gfullname");
            String name = text(f, "name");
            CadastreNames.Part part = name == null ? null : CadastreNames.place(name);
            if (fullName != null && part != null && !f.path("geometry").isNull()) {
                store.adminArea(SOURCE, fullName, part.name(), part.kind(), json.writeValueAsString(f.path("geometry")));
            }
        });

        Map<String, CadastreNames.Locality> localities = new LinkedHashMap<>();
        Map<String, Long> streetIds = new HashMap<>();
        int[] skipped = {0};
        download("Strazi_RM", f -> {
            List<String> parts = CadastreNames.split(text(f, "gfullname"));
            CadastreNames.Locality locality = CadastreNames.locality(parts);
            CadastreNames.Street street = parts.isEmpty() ? null : CadastreNames.street(parts.getLast());
            if (locality == null || street == null) {
                skipped[0]++;
                return;
            }
            localities.putIfAbsent(locality.fullName(), locality);
            String key = streetKey(locality.fullName(), street);
            if (streetIds.containsKey(key)) {
                return; // a street drawn in two pieces
            }
            String geometry = f.path("geometry").isNull() ? null : json.writeValueAsString(f.path("geometry"));
            streetIds.put(key, store.street(SOURCE, f.path("id").asString(), locality.fullName(), street.type(),
                    street.name(), "", List.of(), geometry));
        });
        log.info("cadastre streets: {} stored, {} not streets (roads between places)", streetIds.size(), skipped[0]);

        List<RefStore.Address> batch = new ArrayList<>(PAGE);
        int[] counts = {0, 0};
        download("adresa_point", f -> {
            String number = text(f, "nrcasa");
            JsonNode coords = f.path("geometry").path("coordinates");
            if (number == null || !coords.isArray() || coords.size() < 2) {
                return;
            }
            List<String> parts = CadastreNames.split(text(f, "gfullname"));
            CadastreNames.Locality locality = CadastreNames.locality(parts);
            if (locality == null) {
                return;
            }
            localities.putIfAbsent(locality.fullName(), locality);
            String last = parts.getLast();
            String streetText = last.endsWith(" " + number) ? last.substring(0, last.length() - number.length()).strip() : last;
            CadastreNames.Street street = CadastreNames.place(streetText) != null ? null : CadastreNames.street(streetText);
            Long streetId = street == null ? null : streetIds.get(streetKey(locality.fullName(), street));
            counts[0]++;
            if (streetId != null) {
                counts[1]++;
            }
            batch.add(new RefStore.Address(streetId, locality.fullName(), street == null ? null : streetText, number,
                    coords.get(1).asDouble(), coords.get(0).asDouble()));
            if (batch.size() == PAGE) {
                store.addresses(SOURCE, batch);
                batch.clear();
            }
        });
        store.addresses(SOURCE, batch);
        log.info("cadastre addresses: {} stored, {} linked to their street", counts[0], counts[1]);
        localities.values().forEach(l -> store.locality(SOURCE, l));
        log.info("cadastre: {} locality name variants merged", store.mergeLocalityVariants(SOURCE));
        log.info("cadastre: {} more addresses linked to their street by position", store.linkAddressesByPosition(SOURCE));
        store.finish(SOURCE, clock.instant());
    }

    static String streetKey(String locality, CadastreNames.Street street) {
        return locality + "|" + street.type() + "|" + Text.fold(street.name());
    }

    /** Calls {@code each} for every feature of the layer, one page at a time. */
    private void download(String layer, Consumer<JsonNode> each) throws IOException {
        int start = 0;
        int seen = 0;
        int matched = -1;
        while (true) {
            URI uri = URI.create(WFS + "&typeNames=cadastru_data:" + layer + "&count=" + PAGE + "&startIndex=" + start);
            JsonNode page = json.readTree(http.get(uri, Map.of("Accept", "application/json")).body());
            if (start == 0) {
                matched = page.path("numberMatched").asInt(-1);
            }
            JsonNode features = page.path("features");
            for (JsonNode f : features) {
                each.accept(f);
            }
            seen += features.size();
            if (features.size() < PAGE) {
                break;
            }
            start += PAGE;
        }
        if (matched >= 0 && seen != matched) {
            throw new IOException(layer + ": downloaded " + seen + " of " + matched + " features");
        }
        log.info("cadastre {}: {} features", layer, seen);
    }

    private static String text(JsonNode f, String property) {
        JsonNode v = f.path("properties").path(property);
        return v.isNull() || v.isMissingNode() ? null : v.asString().strip();
    }
}
