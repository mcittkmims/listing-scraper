package md.apartmentlens.scraper.normalize;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Phone numbers: "069 123 456", "+373 69123456", "37369123456", "(0)69-123-456" all become
 * +37369123456 (foreign numbers keep their country code), stored only as an HMAC-SHA256 hash with a
 * secret key. Equal numbers give equal hashes, so ads by the same seller can be linked without keeping
 * the numbers.
 */
public final class PhoneNormalizer {

    private final SecretKeySpec key;

    public PhoneNormalizer(String secret) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("the phone hash key must be at least 16 characters");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /**
     * The number in international form: +373XXXXXXXX for Moldovan numbers (also written 0XX XXX XXX or
     * without a prefix), "+" with country code and number for sellers abroad. Empty when it cannot be a
     * number.
     */
    public static Optional<String> e164(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.startsWith("373") && digits.length() == 11) {
            digits = digits.substring(3);
        } else if (digits.startsWith("0") && digits.length() == 9) {
            digits = digits.substring(1);
        }
        // Moldovan national numbers have 8 digits: mobiles start with 6 or 7, fixed lines with 2 or 3.
        if (digits.length() == 8 && digits.charAt(0) >= '2') {
            return Optional.of("+373" + digits);
        }
        // Foreign numbers: country code and number, 10 to 15 digits in all (E.164), never starting with 0.
        if (digits.length() >= 10 && digits.length() <= 15 && digits.charAt(0) != '0' && !digits.startsWith("373")) {
            return Optional.of("+" + digits);
        }
        return Optional.empty();
    }

    public List<byte[]> hashes(List<String> phones, Issues issues) {
        Set<String> numbers = new LinkedHashSet<>();
        for (String raw : phones) {
            Optional<String> n = e164(raw);
            if (n.isPresent()) {
                numbers.add(n.get());
            } else if (raw != null && !raw.isBlank()) {
                // Keep only the shape of the number in the issue, never the number itself.
                issues.add("phone", raw.replaceAll("\\d", "9"), "unparsed");
            }
        }
        List<byte[]> out = new ArrayList<>(numbers.size());
        for (String n : numbers) {
            out.add(hash(n));
        }
        return out;
    }

    public byte[] hash(String e164) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(e164.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
