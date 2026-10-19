package md.apartmentlens.scraper.source;

import java.math.BigDecimal;

/**
 * A price as published. Sources with structured prices fill {@code amount} and {@code currency};
 * sources with text fill {@code text} ("60 000 €", "1,2 mln lei", "Preț negociabil").
 */
public record RawPrice(BigDecimal amount, String currency, String text, Boolean negotiable, BigDecimal downPayment) {
}
