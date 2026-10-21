package md.apartmentlens.scraper;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import md.apartmentlens.scraper.crawl.CrawlService;
import md.apartmentlens.scraper.crawl.DefaultPinReview;
import md.apartmentlens.scraper.crawl.RenormalizeService;
import md.apartmentlens.scraper.ref.CadastreImporter;
import md.apartmentlens.scraper.ref.OsmImporter;
import md.apartmentlens.scraper.ref.RefGazetteer;
import md.apartmentlens.scraper.source.ListingSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * The scraper service. Without arguments it runs as a service and crawls on its schedule. Commands:
 * <ul>
 *   <li>{@code crawl [SOURCE...]}: one pass over every source, or the ones named (e.g. 999md, proimobil), then exit</li>
 *   <li>{@code renormalize [SOURCE...]}: normalize ads stored by an older normalizer again, then exit;
 *       {@code --all} does every ad</li>
 *   <li>{@code import-ref [cadastre] [osm]}: load the reference data of streets and addresses, then exit;
 *       without arguments both (OpenStreetMap needs its staging tables, see database/osm)</li>
 *   <li>{@code export-gazetteer DIR}: write the ref tables as the bundled gazetteer files</li>
 * </ul>
 */
@SpringBootApplication
public class ScraperApplication {

    public static void main(String[] args) {
        String command = Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse("daemon");
        if (!List.of("daemon", "crawl", "renormalize", "import-ref", "export-gazetteer").contains(command)) {
            System.err.println("unknown command '" + command + "'; use crawl [SOURCE...], renormalize [SOURCE...] [--all], "
                    + "import-ref [cadastre] [osm], export-gazetteer DIR, or no command");
            System.exit(2);
        }
        SpringApplication app = new SpringApplication(ScraperApplication.class);
        app.setDefaultProperties(Map.of("scraper.mode", command));
        ConfigurableApplicationContext context = app.run(args);
        if (!command.equals("daemon")) {
            System.exit(SpringApplication.exit(context));
        }
    }

    @Bean
    ApplicationRunner commands(List<ListingSource> sources, CrawlService crawls, RenormalizeService renormalize,
                               DefaultPinReview pinReview, CadastreImporter cadastre, OsmImporter osm,
                               RefGazetteer refGazetteer, Environment env) {
        return (ApplicationArguments args) -> {
            String mode = env.getProperty("scraper.mode", "daemon");
            switch (mode) {
                case "crawl" -> named(sources, args).forEach(s -> crawls.crawl(s).ifPresent(stats -> pinReview.review(s)));
                case "renormalize" -> named(sources, args).forEach(s -> {
                    renormalize.renormalize(s, args.containsOption("all"));
                    pinReview.review(s); // pins are stored by the step above
                });
                case "import-ref" -> {
                    List<String> what = args.getNonOptionArgs().subList(1, args.getNonOptionArgs().size());
                    if (what.isEmpty() || what.contains("cadastre")) {
                        cadastre.run();
                    }
                    if (what.isEmpty() || what.contains("osm")) {
                        osm.run(); // after the cadastre: links its streets to the cadastre's
                    }
                }
                case "export-gazetteer" -> {
                    List<String> rest = args.getNonOptionArgs();
                    Path dir = Path.of(rest.size() > 1 ? rest.get(1) : "gazetteer");
                    RefGazetteer.export(refGazetteer.load(), dir);
                }
                default -> {
                    // daemon: CrawlScheduler does the work
                }
            }
        };
    }

    /** The sources named after the command, or all of them. */
    static List<ListingSource> named(List<ListingSource> sources, ApplicationArguments args) {
        List<String> codes = args.getNonOptionArgs().subList(1, args.getNonOptionArgs().size());
        if (codes.isEmpty()) {
            return sources;
        }
        List<String> known = sources.stream().map(ListingSource::code).toList();
        List<String> unknown = codes.stream().filter(c -> !known.contains(c)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown sources " + unknown + "; known: " + known);
        }
        return sources.stream().filter(s -> codes.contains(s.code())).toList();
    }
}
