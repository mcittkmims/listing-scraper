package md.apartmentlens.scraper.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void cleanCollapsesSpacesAndFixesCedillas() {
        assertThat(Text.clean("  str.  Ştefan  cel   Mare ​")).isEqualTo("str. Ștefan cel Mare");
        assertThat(Text.clean("   ")).isNull();
        assertThat(Text.clean(null)).isNull();
    }

    @Test
    void cleanMultilineKeepsParagraphs() {
        assertThat(Text.cleanMultiline("Line one  \r\n\r\n\r\n  line   two\n")).isEqualTo("Line one\n\nline two");
    }

    @ParameterizedTest
    @CsvSource({
            "'Ștefan cel Mare și Sfânt', 'stefan cel mare si sfant'",
            "'Ștefan cel Mare și Sfînt', 'stefan cel mare si sfant'",
            "'Râșcani', 'rascani'",
            "'Rîșcani', 'rascani'",
            "'Mircea cel Bătrân', 'mircea cel batran'",
            "'Kирпичный', 'кирпичный'",
            "'Каля Eшилор', 'каля ешилор'",
            "'Alba-Iulia', 'alba iulia'",
            "'Кишинёв', 'кишинев'",
            "'Измайловская', 'измайловская'",
    })
    void foldGivesOneKeyForEverySpelling(String in, String key) {
        assertThat(Text.fold(in)).isEqualTo(key);
    }

    @ParameterizedTest
    @CsvSource({
            "'Каля Ешилор', 'calia iesilor'",
            "'Чеукарь', 'ceucari'",
            "'Мирча чел Бэтрын', 'mircia cel batran'",
            "'Дачия', 'dacia'",
            "'Эминеску', 'eminescu'",
            "'Реджеле', 'regele'",
            "'Штефан чел Маре ши Сфынт', 'stefan cel mare si sfant'",
    })
    void transliteratesRussianSpellingOfRomanianNames(String in, String key) {
        assertThat(Text.cyrillicToLatinKey(in)).isEqualTo(key);
    }

    @Test
    void similarity() {
        assertThat(Text.similarity("ismail", "ismail")).isEqualTo(1.0);
        assertThat(Text.similarity("grenoble", "grenobl")).isGreaterThan(0.85);
        assertThat(Text.similarity("dacia", "moscova")).isLessThan(0.3);
    }
}
