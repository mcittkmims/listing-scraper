package md.apartmentlens.scraper.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PhoneNormalizerTest {

    final PhoneNormalizer phones = new PhoneNormalizer("test-key-0123456789abcdef");

    @ParameterizedTest
    @CsvSource({
            "'37360000001', '+37360000001'",
            "'+373 60 000 001', '+37360000001'",
            "'060 000 001', '+37360000001'",
            "'(0)60-000-001', '+37360000001'",
            "'60000001', '+37360000001'",
            "'0037322000001', '+37322000001'",
            "'40700000001', '+40700000001'",
            "'393200000001', '+393200000001'",
    })
    void international(String raw, String e164) {
        assertThat(PhoneNormalizer.e164(raw)).hasValue(e164);
    }

    @Test
    void notNumbers() {
        assertThat(PhoneNormalizer.e164("123")).isEmpty();
        assertThat(PhoneNormalizer.e164("012345678901")).isEmpty();
    }

    @Test
    void equalNumbersGiveEqualHashesAndTheNumberIsNotKept() {
        Issues issues = new Issues();
        List<byte[]> hashes = phones.hashes(List.of("37360000001", "060 000 001", "12"), issues);
        assertThat(hashes).hasSize(1);
        assertThat(hashes.getFirst()).hasSize(32);
        assertThat(issues.list()).containsExactly(new Issue("phone", "99", "unparsed"));
        assertThat(new PhoneNormalizer("another-key-0123456789").hash("+37360000001")).isNotEqualTo(hashes.getFirst());
    }

    @Test
    void shortKeysAreRefused() {
        assertThatThrownBy(() -> new PhoneNormalizer("short")).isInstanceOf(IllegalArgumentException.class);
    }
}
