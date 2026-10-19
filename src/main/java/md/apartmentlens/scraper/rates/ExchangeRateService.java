package md.apartmentlens.scraper.rates;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import md.apartmentlens.scraper.store.ExchangeRateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exchange rates from the database, fetched from BNM when a day is missing. BNM publishes no rates on
 * weekends and holidays; then the most recent earlier rate (at most {@value #MAX_AGE_DAYS} days old) applies.
 */
public class ExchangeRateService implements ExchangeRates {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateService.class);
    static final int MAX_AGE_DAYS = 10;
    static final Set<String> CURRENCIES = Set.of("EUR", "USD", "RUB", "RON", "UAH", "GBP");

    private final ExchangeRateRepository repository;
    private final BnmRateClient bnm;
    private final Map<String, Optional<BigDecimal>> cache = new ConcurrentHashMap<>();

    public ExchangeRateService(ExchangeRateRepository repository, BnmRateClient bnm) {
        this.repository = repository;
        this.bnm = bnm;
    }

    /** Makes sure the rates of a day are stored; call before normalizing ads of that day. */
    public void ensure(LocalDate date) {
        if (repository.has(date)) {
            return;
        }
        try {
            Map<String, BigDecimal> rates = bnm.rates(date);
            rates.entrySet().removeIf(e -> !CURRENCIES.contains(e.getKey()));
            repository.save(date, rates);
            cache.clear();
            log.info("BNM rates for {}: EUR {}, USD {}", date, rates.get("EUR"), rates.get("USD"));
        } catch (IOException e) {
            log.warn("BNM rates for {} not available ({}); the latest stored rates will be used", date, e.getMessage());
        }
    }

    @Override
    public Optional<BigDecimal> mdlPerUnit(String currency, LocalDate date) {
        if (currency.equals("MDL")) {
            return Optional.of(BigDecimal.ONE);
        }
        return cache.computeIfAbsent(currency + "@" + date,
                k -> repository.latest(currency, date, date.minusDays(MAX_AGE_DAYS)));
    }
}
