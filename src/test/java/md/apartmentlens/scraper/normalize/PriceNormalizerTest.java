package md.apartmentlens.scraper.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import md.apartmentlens.scraper.source.RawPrice;
import org.junit.jupiter.api.Test;

class PriceNormalizerTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 19);
    final PriceNormalizer prices = new PriceNormalizer((currency, date) -> Optional.of(switch (currency) {
        case "EUR" -> new BigDecimal("20.0000");
        case "USD" -> new BigDecimal("18.0000");
        default -> BigDecimal.ONE;
    }));

    PriceNormalizer.Result structured(long amount, String unit, String type, Integer area, Issues issues) {
        return prices.normalize(new RawPrice(BigDecimal.valueOf(amount), unit, null, false, null), type,
                area == null ? null : BigDecimal.valueOf(area), DAY, issues);
    }

    @Test
    void eurStaysAsIs() {
        Issues issues = new Issues();
        var r = structured(67_520, "UNIT_EUR", "apartment", 42, issues);
        assertThat(r.currency()).isEqualTo("EUR");
        assertThat(r.eur()).isEqualByComparingTo("67520");
        assertThat(r.eurPerM2()).isEqualByComparingTo("1607.62");
        assertThat(issues.list()).isEmpty();
    }

    @Test
    void usdAndMdlAreConvertedAtTheBnmRate() {
        assertThat(structured(100_000, "UNIT_USD", "apartment", 60, new Issues()).eur()).isEqualByComparingTo("90000");
        assertThat(structured(1_200_000, "UNIT_MDL", "apartment", 60, new Issues()).eur()).isEqualByComparingTo("60000");
    }

    @Test
    void ninetyNineNineNineNineIsARealPrice() {
        // 19 flats of 45-75 m² in Chișinău were listed at 99 999 € in the 2026-10 snapshot.
        Issues issues = new Issues();
        assertThat(structured(99_999, "UNIT_EUR", "apartment", 45, issues).eur()).isEqualByComparingTo("99999");
        assertThat(issues.list()).isEmpty();
    }

    @Test
    void placeholderDigitsWithAnImpossiblePricePerM2() {
        Issues issues = new Issues();
        var r = structured(12_121_212, "UNIT_EUR", "house", 21, issues);
        assertThat(r.amount()).isEqualByComparingTo("12121212");
        assertThat(r.eur()).isNull();
        assertThat(issues.list()).extracting(Issue::reason).containsExactly("placeholder");

        Issues other = new Issues();
        assertThat(structured(987_654, "UNIT_EUR", "apartment", 92, other).eur()).isNull();
        assertThat(other.list()).extracting(Issue::reason).containsExactly("placeholder");
    }

    @Test
    void cheapVillageHousesAreReal_cheapFlatsAreNot() {
        Issues house = new Issues();
        assertThat(structured(2_200, "UNIT_EUR", "house", 100, house).eur()).isEqualByComparingTo("2200");
        assertThat(house.list()).isEmpty();

        Issues flat = new Issues();
        var r = structured(3_000, "UNIT_MDL", "apartment", 27, flat); // 150 €
        assertThat(r.eur()).isNull();
        assertThat(r.amount()).isEqualByComparingTo("3000");
        assertThat(flat.list()).extracting(Issue::reason).containsExactly("implausible_low");
    }

    @Test
    void textPrices() {
        Issues issues = new Issues();
        var r = prices.normalize(new RawPrice(null, null, "60 de mii €", null, null), "apartment", null, DAY, issues);
        assertThat(r.eur()).isEqualByComparingTo("60000");
        var lei = prices.normalize(new RawPrice(null, null, "1 200 000 lei", null, null), "apartment", null, DAY, issues);
        assertThat(lei.currency()).isEqualTo("MDL");
        var neg = prices.normalize(new RawPrice(null, null, "Preț negociabil", null, null), "apartment", null, DAY, issues);
        assertThat(neg.negotiable()).isTrue();
        assertThat(neg.amount()).isNull();
        assertThat(issues.list()).isEmpty();
    }

    @Test
    void missingRateLeavesEurEmpty() {
        PriceNormalizer noRates = new PriceNormalizer((c, d) -> Optional.empty());
        Issues issues = new Issues();
        var r = noRates.normalize(new RawPrice(BigDecimal.valueOf(50_000), "UNIT_USD", null, false, null), "apartment",
                null, DAY, issues);
        assertThat(r.amount()).isEqualByComparingTo("50000");
        assertThat(r.eur()).isNull();
        assertThat(issues.list()).extracting(Issue::reason).containsExactly("no_rate");
    }

    @Test
    void placeholderPatterns() {
        assertThat(PriceNormalizer.isPlaceholder(new BigDecimal("1111111"))).isTrue();
        assertThat(PriceNormalizer.isPlaceholder(new BigDecimal("123456"))).isTrue();
        assertThat(PriceNormalizer.isPlaceholder(new BigDecimal("12121212"))).isTrue();
        assertThat(PriceNormalizer.isPlaceholder(new BigDecimal("67520"))).isFalse();
        assertThat(PriceNormalizer.isPlaceholder(new BigDecimal("23456"))).isFalse();
    }
}
