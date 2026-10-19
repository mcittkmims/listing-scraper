package md.apartmentlens.scraper.store;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@code ingest.exchange_rate}. */
public class ExchangeRateRepository {

    private final JdbcClient jdbc;

    public ExchangeRateRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean has(LocalDate date) {
        return jdbc.sql("select exists (select 1 from ingest.exchange_rate where rate_date = ? and currency = 'EUR')")
                .param(date).query(Boolean.class).single();
    }

    public void save(LocalDate date, Map<String, BigDecimal> rates) {
        rates.forEach((currency, value) -> jdbc.sql("""
                        insert into ingest.exchange_rate (rate_date, currency, mdl_per_unit) values (?, ?, ?)
                        on conflict (rate_date, currency) do update set mdl_per_unit = excluded.mdl_per_unit,
                                                                        fetched_at = now()
                        """)
                .params(date, currency, value).update());
    }

    /** The rate of the latest day on or before {@code date}, not older than {@code oldest}. */
    public Optional<BigDecimal> latest(String currency, LocalDate date, LocalDate oldest) {
        return jdbc.sql("""
                        select mdl_per_unit from ingest.exchange_rate
                        where currency = ? and rate_date <= ? and rate_date >= ?
                        order by rate_date desc limit 1
                        """)
                .params(currency, date, oldest).query(BigDecimal.class).optional();
    }
}
