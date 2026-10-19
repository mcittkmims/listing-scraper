package md.apartmentlens.scraper.normalize;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import md.apartmentlens.scraper.rates.ExchangeRates;
import md.apartmentlens.scraper.source.RawPrice;
import md.apartmentlens.scraper.text.Numbers;
import md.apartmentlens.scraper.text.Text;

/**
 * Prices: amount and currency as published, plus a EUR value at that day's BNM rate.
 *
 * <p>The EUR value is left empty when the price cannot be real for the kind of property (placeholder
 * digits such as 12121212 for a 21 m² house, a few hundred euros for a flat), so it never distorts maps
 * or statistics.
 * The published amount is always kept.
 */
public final class PriceNormalizer {

    static final BigDecimal MAX_EUR = BigDecimal.valueOf(20_000_000);
    static final BigDecimal MAX_EUR_PER_M2 = BigDecimal.valueOf(20_000);
    static final BigDecimal PLACEHOLDER_MIN_PER_M2 = BigDecimal.valueOf(100);
    static final BigDecimal PLACEHOLDER_MAX_PER_M2 = BigDecimal.valueOf(6_000);

    private static final Pattern EUR = Pattern.compile("€|\\beur(o|os)?\\b|\\bевро\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern USD = Pattern.compile("\\$|\\busd\\b|\\bdolari?\\b|\\bдолл", Pattern.CASE_INSENSITIVE);
    private static final Pattern MDL = Pattern.compile("\\bmdl\\b|\\blei\\b|\\bleu\\b|\\bлей\\b|\\bлеев\\b|\\bлея\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NEGOTIABLE = Pattern.compile("negociabil|negociere|договорн|торг",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final ExchangeRates rates;

    public PriceNormalizer(ExchangeRates rates) {
        this.rates = rates;
    }

    public record Result(BigDecimal amount, String currency, BigDecimal eur, BigDecimal eurPerM2,
                         Boolean negotiable, BigDecimal downPayment) {

        static final Result EMPTY = new Result(null, null, null, null, null, null);
    }

    public Result normalize(RawPrice raw, String propertyType, BigDecimal areaM2, LocalDate date, Issues issues) {
        if (raw == null || (raw.amount() == null && Text.clean(raw.text()) == null)) {
            issues.add("price", null, "missing");
            return Result.EMPTY;
        }
        String text = Text.clean(raw.text());
        Boolean negotiable = raw.negotiable();
        if (text != null && NEGOTIABLE.matcher(text).find()) {
            negotiable = Boolean.TRUE;
        }

        BigDecimal amount = raw.amount() != null ? raw.amount() : Numbers.parseAmount(text).orElse(null);
        String currency = currency(raw.currency()).or(() -> currency(text)).orElse(null);
        BigDecimal downPayment = positive(raw.downPayment());
        if (amount == null) {
            if (negotiable == null || !negotiable) {
                issues.add("price", text, "unparsed");
            }
            return new Result(null, currency, null, null, negotiable, downPayment);
        }
        if (amount.signum() <= 0) {
            issues.add("price", amount.toPlainString(), "not_positive");
            return new Result(null, currency, null, null, negotiable, downPayment);
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (currency == null) {
            issues.add("price_currency", raw.currency() != null ? raw.currency() : text, "unparsed");
            return new Result(amount, null, null, null, negotiable, downPayment);
        }

        BigDecimal eur = toEur(amount, currency, date, issues);
        BigDecimal perM2 = null;
        if (eur != null && areaM2 != null && areaM2.signum() > 0) {
            perM2 = eur.divide(areaM2, 2, RoundingMode.HALF_UP);
        }
        if (eur != null) {
            String reason = implausible(amount, eur, perM2, propertyType);
            if (reason != null) {
                issues.add("price", amount.toPlainString() + " " + currency, reason);
                eur = null;
                perM2 = null;
            }
        }
        if (perM2 != null && perM2.compareTo(MAX_EUR_PER_M2) > 0) {
            issues.add("price_per_m2", perM2.toPlainString(), "implausible");
            perM2 = null;
        }
        return new Result(amount, currency, eur, perM2, negotiable, downPayment);
    }

    private BigDecimal toEur(BigDecimal amount, String currency, LocalDate date, Issues issues) {
        if (currency.equals("EUR")) {
            return amount;
        }
        Optional<BigDecimal> from = rates.mdlPerUnit(currency, date);
        Optional<BigDecimal> eur = rates.mdlPerUnit("EUR", date);
        if (from.isEmpty() || eur.isEmpty()) {
            issues.add("price_eur", currency + " " + date, "no_rate");
            return null;
        }
        return amount.multiply(from.get()).divide(eur.get(), 2, RoundingMode.HALF_UP);
    }

    /** Minimum believable sale price in EUR for each kind of property. */
    static BigDecimal minimumEur(String propertyType) {
        return BigDecimal.valueOf(switch (propertyType == null ? "" : propertyType) {
            case "apartment" -> 2_500;
            case "room" -> 1_000;
            case "country_house" -> 500;
            default -> 1_500;
        });
    }

    /**
     * Why a price cannot be real, or null. Placeholder-looking digits alone are not enough: 99 999 € is a
     * common real price. They count only when the price per m² is not believable or the area is unknown.
     */
    static String implausible(BigDecimal amount, BigDecimal eur, BigDecimal eurPerM2, String propertyType) {
        if (isPlaceholder(amount) && (eurPerM2 == null || eurPerM2.compareTo(PLACEHOLDER_MIN_PER_M2) < 0
                || eurPerM2.compareTo(PLACEHOLDER_MAX_PER_M2) > 0)) {
            return "placeholder";
        }
        if (eur.compareTo(minimumEur(propertyType)) < 0) {
            return "implausible_low";
        }
        if (eur.compareTo(MAX_EUR) > 0) {
            return "implausible_high";
        }
        return null;
    }

    /** Digits typed to fill the field: 99999, 1111111, 123456, 987654, 12121212. */
    static boolean isPlaceholder(BigDecimal amount) {
        String digits = amount.stripTrailingZeros().toPlainString();
        if (digits.contains(".") || digits.length() < 5) {
            return false;
        }
        if (digits.chars().distinct().count() == 1) {
            return true;
        }
        if ("1234567890".startsWith(digits) || "9876543210".startsWith(digits)) {
            return true;
        }
        // A two-digit pattern repeated: 121212, 12121212.
        if (digits.length() >= 6) {
            String unit = digits.substring(0, 2);
            return unit.charAt(0) != unit.charAt(1) && unit.repeat(digits.length() / 2).equals(digits);
        }
        return false;
    }

    static Optional<String> currency(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String t = s.strip().toUpperCase(Locale.ROOT);
        switch (t) {
            case "UNIT_EUR", "EUR", "€" -> {
                return Optional.of("EUR");
            }
            case "UNIT_USD", "USD", "$" -> {
                return Optional.of("USD");
            }
            case "UNIT_MDL", "MDL", "LEI" -> {
                return Optional.of("MDL");
            }
            default -> {
            }
        }
        int found = 0;
        String result = null;
        if (EUR.matcher(s).find()) {
            found++;
            result = "EUR";
        }
        if (USD.matcher(s).find()) {
            found++;
            result = "USD";
        }
        if (MDL.matcher(s).find()) {
            found++;
            result = "MDL";
        }
        return found == 1 ? Optional.of(result) : Optional.empty();
    }

    private static BigDecimal positive(BigDecimal v) {
        return v != null && v.signum() > 0 ? v.setScale(2, RoundingMode.HALF_UP) : null;
    }
}
