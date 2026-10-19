package md.apartmentlens.scraper;

import java.util.Arrays;
import java.util.List;
import md.apartmentlens.scraper.crawl.CrawlService;
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
 * </ul>
 */
@SpringBootApplication
public class ScraperApplication {

    public static void main(String[] args) {
        String command = Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse("daemon");
        if (!List.of("daemon", "crawl", "renormalize").contains(command)) {
            System.err.println("unknown command '" + command + "'; use crawl, renormalize [--all], or no command");
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
                               org.springframework.core.env.Environment env) {
        return (ApplicationArguments args) -> {
            String mode = env.getProperty("scraper.mode", "daemon");
            switch (mode) {
                case "crawl" -> sources.forEach(crawls::crawl);
                case "renormalize" -> sources.forEach(s -> renormalize.renormalize(s, args.containsOption("all")));
                default -> {
                    // daemon: CrawlScheduler does the work
                }
            }
        };
    }
}
