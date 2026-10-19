package md.apartmentlens.scraper.rates;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/** Official BNM exchange rates: how many lei one unit of a currency costs on a date. */
public interface ExchangeRates {

    /**
     * The rate for the date, or the most recent earlier rate (BNM publishes none on holidays).
     * MDL always returns 1.
     */
    Optional<BigDecimal> mdlPerUnit(String currency, LocalDate date);
}
