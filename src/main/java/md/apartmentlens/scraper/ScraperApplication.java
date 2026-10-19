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
 *   <li>{@code import-ref cadastre}: load the reference data of streets and addresses, then exit</li>
 * </ul>
 */
@SpringBootApplication
public class ScraperApplication {

    public static void main(String[] args) {
        String command = Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse("daemon");
        if (!List.of("daemon", "crawl", "renormalize", "import-ref").contains(command)) {
            System.err.println("unknown command '" + command
                    + "'; use crawl, renormalize [--all], import-ref cadastre, or no command");
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
                }
                default -> {
                    // daemon: CrawlScheduler does the work
                }
            }
        };
    }
}
