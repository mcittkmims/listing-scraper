package md.apartmentlens.scraper;

import java.time.Clock;
import java.time.Duration;
import md.apartmentlens.scraper.crawl.CrawlService;
import md.apartmentlens.scraper.crawl.RenormalizeService;
import md.apartmentlens.scraper.http.PoliteHttpClient;
import md.apartmentlens.scraper.normalize.ListingNormalizer;
import md.apartmentlens.scraper.normalize.PhoneNormalizer;
import md.apartmentlens.scraper.normalize.PriceNormalizer;
import md.apartmentlens.scraper.normalize.address.AddressNormalizer;
import md.apartmentlens.scraper.normalize.address.PlaceGazetteer;
import md.apartmentlens.scraper.normalize.address.StreetGazetteer;
import md.apartmentlens.scraper.rates.BnmRateClient;
import md.apartmentlens.scraper.rates.ExchangeRateService;
import md.apartmentlens.scraper.source.ninetynine.NineNineSource;
import md.apartmentlens.scraper.store.CrawlRunRepository;
import md.apartmentlens.scraper.store.ExchangeRateRepository;
import md.apartmentlens.scraper.store.ListingRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(ScraperProperties.class)
class ScraperConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** One client for all crawling, so the pause between requests holds across sources. */
    @Bean
    PoliteHttpClient politeHttpClient(ScraperProperties p) {
        return new PoliteHttpClient(p.userAgent(), p.requestInterval(), p.maxAttempts(), p.requestTimeout());
    }

    @Bean
    NineNineSource nineNineSource(PoliteHttpClient http, JsonMapper json, ScraperProperties p) {
        return new NineNineSource(http, json, p.maxPages());
    }

    @Bean
    ExchangeRateRepository exchangeRateRepository(JdbcClient jdbc) {
        return new ExchangeRateRepository(jdbc);
    }

    @Bean
    ExchangeRateService exchangeRateService(ExchangeRateRepository repository) {
        // BNM gets its own client: a rate request must not wait behind the crawl's pauses.
        PoliteHttpClient http = new PoliteHttpClient(
                "apartment-lens-scraper/1.0 (+https://github.com/mcittkmims/apartment-lens)",
                Duration.ofSeconds(1), 4, Duration.ofSeconds(30));
        return new ExchangeRateService(repository, new BnmRateClient(http));
    }

    @Bean
    CrawlRunRepository crawlRunRepository(JdbcClient jdbc) {
        return new CrawlRunRepository(jdbc);
    }

    @Bean
    ListingRepository listingRepository(JdbcClient jdbc, JsonMapper json) {
        return new ListingRepository(jdbc, json);
    }

    @Bean
    ListingNormalizer listingNormalizer(ExchangeRateService rates, ScraperProperties p) {
        return new ListingNormalizer(new PriceNormalizer(rates), PlaceGazetteer.load(),
                new AddressNormalizer(StreetGazetteer.load()), new PhoneNormalizer(p.phoneHashKey()));
    }

    @Bean
    CrawlService crawlService(CrawlRunRepository runs, ListingRepository listings, ListingNormalizer normalizer,
                              ExchangeRateService rates, TransactionTemplate tx, Clock clock, PoliteHttpClient http,
                              ScraperProperties p) {
        return new CrawlService(runs, listings, normalizer, rates, tx, clock, http::requestCount,
                new CrawlService.Safety(CrawlService.Safety.DEFAULT.staleRunAfter(), p.minSeenShare(), p.maxGoneShare()));
    }

    @Bean
    RenormalizeService renormalizeService(CrawlRunRepository runs, ListingRepository listings,
                                          ListingNormalizer normalizer, ExchangeRateService rates,
                                          TransactionTemplate tx) {
        return new RenormalizeService(runs, listings, normalizer, rates, tx);
    }
}
