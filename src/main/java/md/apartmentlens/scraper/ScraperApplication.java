package md.apartmentlens.scraper;

import java.util.Arrays;
import java.util.List;
import md.apartmentlens.scraper.crawl.CrawlService;
import md.apartmentlens.scraper.crawl.DefaultPinReview;
import md.apartmentlens.scraper.crawl.RenormalizeService;
import md.apartmentlens.scraper.source.ListingSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * The scraper service. Without arguments it runs as a service and crawls on its schedule. Commands:
 * <ul>
 *   <li>{@code crawl}: one pass over every source, then exit</li>
 *   <li>{@code renormalize}: normalize ads stored by an older normalizer again, then exit;
 *       {@code renormalize --all} does every ad</li>
 *   <li>{@code import-ref [cadastre] [osm] [mapmd]}: load the reference data of streets and addresses, then
 *       exit; OpenStreetMap needs its staging tables first (database/osm), map.md is read only when named</li>
 *   <li>{@code export-gazetteer DIR}: write the ref tables as the bundled gazetteer files</li>
 * </ul>
 */
@SpringBootApplication
public class ScraperApplication {

    public static void main(String[] args) {
        String command = Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse("daemon");
        if (!List.of("daemon", "crawl", "renormalize", "import-ref", "export-gazetteer").contains(command)) {
            System.err.println("unknown command '" + command
                    + "'; use crawl, renormalize [--all], import-ref [cadastre] [osm] [mapmd], or no command");
            System.exit(2);
        }
        SpringApplication app = new SpringApplication(ScraperApplication.class);
        app.setDefaultProperties(java.util.Map.of("scraper.mode", command));
        ConfigurableApplicationContext context = app.run(args);
        if (!command.equals("daemon")) {
            System.exit(SpringApplication.exit(context));
        }
    }

    @Bean
    ApplicationRunner commands(List<ListingSource> sources, CrawlService crawls, RenormalizeService renormalize,
                               DefaultPinReview pinReview, md.apartmentlens.scraper.ref.CadastreImporter cadastre,
                               md.apartmentlens.scraper.ref.OsmImporter osm,
                               md.apartmentlens.scraper.ref.MapMdImporter mapmd,
                               md.apartmentlens.scraper.ref.RefGazetteer refGazetteer,
                               org.springframework.core.env.Environment env) {
        return (ApplicationArguments args) -> {
            String mode = env.getProperty("scraper.mode", "daemon");
            switch (mode) {
                case "crawl" -> sources.forEach(s -> crawls.crawl(s).ifPresent(stats -> pinReview.review(s)));
                case "renormalize" -> sources.forEach(s -> {
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
                    if (what.contains("mapmd")) {
                        mapmd.run(); // only when asked for: map.md publishes no licence
                    }
                }
                case "export-gazetteer" -> {
                    List<String> rest = args.getNonOptionArgs();
                    java.nio.file.Path dir = java.nio.file.Path.of(rest.size() > 1 ? rest.get(1) : "gazetteer");
                    md.apartmentlens.scraper.ref.RefGazetteer.export(refGazetteer.load(), dir);
                }
                default -> {
                    // daemon: CrawlScheduler does the work
                }
            }
        };
    }
}
