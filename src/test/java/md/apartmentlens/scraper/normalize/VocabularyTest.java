package md.apartmentlens.scraper.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Every option label 999.md offers (Romanian and Russian interface, October 2026) has a code. */
class VocabularyTest {

    static void assertCode(Vocabulary v, String label, String code) {
        Issues issues = new Issues();
        assertThat(v.code(label, issues)).as(label).hasValue(code);
        assertThat(issues.list()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "Vând, sale", "Продам, sale", "Cumpăr, buy", "Куплю, buy", "Închiriez, rent", "De închiriat lunar, rent",
            "Сдаю помесячно, rent", "De închiriat pe zi, rent_daily", "Сдаю посуточно, rent_daily", "Schimb, exchange",
            "Меняю, exchange", "Сниму, rent_wanted",
    })
    void offer(String label, String code) {
        assertCode(Vocabulary.OFFER, label, code);
    }

    @ParameterizedTest
    @CsvSource({
            "Persoană fizică, owner", "Частное лицо, owner", "Agenție, agency", "Агентство, agency",
            "Dezvoltator imobiliar, developer", "Застройщик, developer", "Bancă, bank", "Банк, bank",
    })
    void seller(String label, String code) {
        assertCode(Vocabulary.SELLER, label, code);
    }

    @ParameterizedTest
    @CsvSource({
            "Construcţii noi, new_build", "Новострой, new_build", "Secundar, secondary", "Вторичный, secondary",
    })
    void housingStock(String label, String code) {
        assertCode(Vocabulary.HOUSING_STOCK, label, code);
    }

    @ParameterizedTest
    @CsvSource({
            "Panou, panel", "Панельный, panel", "Cotileț, limestone", "Kотельцовый, limestone", "Cărămidă, brick",
            "Kирпичный, brick", "Bloc, block", "Блочный, block", "Monolit, monolith", "Mонолитный, monolith",
            "Beton, concrete", "Бетон, concrete", "Combinat, mixed", "Kомбинированный, mixed",
            "Beton celular, aerated_concrete", "Ячеистый Бетон, aerated_concrete", "Lemn, wood", "Дерево, wood",
            "Din chirpici, adobe", "Саманный, adobe", "Altul, other", "Другой, other",
    })
    void material(String label, String code) {
        // Several Russian labels on 999.md start with a Latin letter ("Kирпичный").
        assertCode(Vocabulary.MATERIAL, label, code);
    }

    @ParameterizedTest
    @CsvSource({
            "102, 102", "135, 135", "143, 143", "Ms (serie  moldovenească), ms", "Мс (Молд. Серия), ms",
            "Brj (Brejnevka), brezhnevka", "Брж (Брежневка), brezhnevka", "Hruşciovka, khrushchyovka",
            "Хрущевка, khrushchyovka", "St (Stalinka), stalinka", "Ст (Сталинка), stalinka", "Cş (Ceşka), ceshka",
            "Чш (Чешка), ceshka", "Varț (Varnițkaia), varnitskaya", "Варц (Варницкая), varnitskaya",
            "Rubaşka, rubashka", "Рубашка, rubashka", "Cămin familial, family_dorm", "Малосемейная, family_dorm",
            "Garsonieră, studio", "Гостинка, studio", "Pe pămănt, ground_floor_house", "На земле, ground_floor_house",
            "Individuală, individual", "Индивидуальная, individual",
    })
    void series(String label, String code) {
        assertCode(Vocabulary.SERIES, label, code);
    }

    @ParameterizedTest
    @CsvSource({
            "Variantă albă, white_shell", "Белый вариант, white_shell", "Variantă sură, grey_shell",
            "Cерый вариант, grey_shell", "Euroreparație, euro_renovation", "Eвроремонт, euro_renovation",
            "Евроремонт, euro_renovation", "Reparație cosmetică, cosmetic_renovation",
            "Косметический pемонт, cosmetic_renovation", "Design individual, individual_design",
            "Индивидуальный дизайн, individual_design", "Fără reparație, no_renovation", "Без ремонта, no_renovation",
            "Are nevoie de reparație, needs_renovation", "Нуждается в ремонте, needs_renovation",
            "Construcție nefinisată, unfinished", "Незавершенное cтроительство, unfinished",
            "Dat în exploatare, commissioned", "Сдан в эксплуатацию, commissioned", "Casă de demolat, to_demolish",
            "Дом под снос, to_demolish",
    })
    void condition(String label, String code) {
        assertCode(Vocabulary.CONDITION, label, code);
    }

    @ParameterizedTest
    @CsvSource({
            "Deschis, open", "Oткрытое, open", "Garaj, garage", "Гараж, garage", "Sub acoperiș, covered",
            "Под навесом, covered", "Subterană, underground", "Подземная, underground",
    })
    void parking(String label, String code) {
        assertCode(Vocabulary.PARKING, label, code);
    }

    @ParameterizedTest
    @CsvSource({"Casă, house", "Дом, house", "Townhouse, townhouse", "Таунхаус, townhouse", "Duplex, duplex",
            "Дуплекс, duplex", "Vilă, villa"})
    void houseType(String label, String code) {
        assertCode(Vocabulary.HOUSE_TYPE, label, code);
    }

    @Test
    void unknownLabelsAreReportedNotGuessed() {
        Issues issues = new Issues();
        assertThat(Vocabulary.MATERIAL.code("Sticlă", issues)).isEmpty();
        assertThat(issues.list()).containsExactly(new Issue("building_material", "Sticlă", "unknown_label"));
    }
}
