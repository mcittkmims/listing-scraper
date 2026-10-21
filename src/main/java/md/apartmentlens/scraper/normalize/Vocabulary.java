package md.apartmentlens.scraper.normalize;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import md.apartmentlens.scraper.text.Text;

/**
 * Fixed lists of values (seller type, building material, condition, ...) mapped to stable codes.
 * Labels are matched after {@link Text#fold}, so diacritics and case do not matter. Each list holds the
 * Romanian and Russian labels used by the sources; an unknown label is reported, never guessed.
 */
public final class Vocabulary {

    private final String field;
    private final Map<String, String> codes = new HashMap<>();

    private Vocabulary(String field) {
        this.field = field;
    }

    private Vocabulary put(String code, String... labels) {
        for (String label : labels) {
            String key = Text.fold(label);
            String previous = codes.put(key, code);
            if (previous != null && !previous.equals(code)) {
                throw new IllegalStateException(field + ": label '" + label + "' maps to " + previous + " and " + code);
            }
        }
        codes.put(Text.fold(code), code);
        return this;
    }

    /** The code for a label; reports an issue for a label that is not in the list. */
    public Optional<String> code(String label, Issues issues) {
        String clean = Text.clean(label);
        if (clean == null) {
            return Optional.empty();
        }
        String code = codes.get(Text.fold(clean));
        if (code == null) {
            issues.add(field, clean, "unknown_label");
        }
        return Optional.ofNullable(code);
    }

    public String field() {
        return field;
    }

    public static final Vocabulary OFFER = new Vocabulary("offer_type")
            .put("sale", "Vând", "Vînd", "Vânzare", "Продам", "Продаю", "Продажа")
            .put("buy", "Cumpăr", "Куплю")
            .put("rent", "Închiriez", "De închiriat lunar", "Сдаю", "Сдаю помесячно", "Аренда")
            .put("rent_wanted", "Caut chirie", "Сниму")
            .put("rent_daily", "De închiriat pe zi", "Посуточно", "Сдаю посуточно")
            .put("exchange", "Schimb", "Меняю", "Обмен");

    public static final Vocabulary SELLER = new Vocabulary("seller_type")
            .put("owner", "Persoană fizică", "Proprietar", "Частное лицо", "Собственник", "Хозяин")
            .put("agency", "Agenție", "Agenție imobiliară", "Агентство", "Агентство недвижимости")
            .put("developer", "Dezvoltator imobiliar", "Dezvoltator", "Застройщик")
            .put("bank", "Bancă", "Банк");

    public static final Vocabulary HOUSING_STOCK = new Vocabulary("housing_stock")
            .put("new_build", "Construcții noi", "Construcție nouă", "Bloc nou", "Новостройки", "Новостройка", "Новострой")
            .put("secondary", "Secundar", "Fond secundar", "Вторичное", "Вторичный", "Вторичка");

    public static final Vocabulary MATERIAL = new Vocabulary("building_material")
            .put("panel", "Panou", "Panouri", "Панель", "Панельный")
            .put("limestone", "Cotileț", "Котелец", "Котельцовый")
            .put("brick", "Cărămidă", "Кирпич", "Кирпичный")
            .put("block", "Bloc", "Блок", "Блочный")
            .put("monolith", "Monolit", "Монолит", "Монолитный")
            .put("concrete", "Beton", "Бетон")
            .put("mixed", "Combinat", "Комбинированный", "Смешанный")
            .put("aerated_concrete", "Beton celular", "BCA", "Газобетон", "Газоблок", "Ячеистый бетон")
            .put("wood", "Lemn", "Дерево", "Деревянный")
            .put("adobe", "Din chirpici", "Chirpici", "Саман", "Из самана", "Саманный")
            .put("other", "Altul", "Altele", "Другое", "Другой");

    /** Building series or layout ("compartimentare"): Soviet series, Moldovan series, individual layout. */
    public static final Vocabulary SERIES = new Vocabulary("building_series")
            .put("102", "102", "Seria 102", "102 серия")
            .put("135", "135", "Seria 135", "135 серия")
            .put("143", "143", "Seria 143", "143 серия")
            .put("ms", "Ms (serie moldovenească)", "Ms", "Serie moldovenească", "МС (молдавская серия)", "МС", "Молдавская серия", "Мс (Молд. Серия)")
            .put("brezhnevka", "Brj (Brejnevka)", "Brejnevka", "Брежневка", "Брж (брежневка)")
            .put("khrushchyovka", "Hruşciovka", "Hrușciovka", "Хрущевка", "Хрущёвка")
            .put("stalinka", "St (Stalinka)", "Stalinka", "Сталинка", "Ст (сталинка)")
            .put("ceshka", "Cş (Ceşka)", "Ceșka", "Чешка", "Чш (чешка)")
            .put("varnitskaya", "Varț (Varnițkaia)", "Varnițkaia", "Варницкая", "Варн (варницкая)", "Варц (Варницкая)")
            .put("rubashka", "Rubaşka", "Rubașka", "Рубашка")
            .put("family_dorm", "Cămin familial", "Семейное общежитие", "Малосемейная", "Малосемейка")
            .put("studio", "Garsonieră", "Гостинка", "Гостинка (гарсоньера)")
            .put("ground_floor_house", "Pe pămănt", "Pe pământ", "На земле")
            .put("individual", "Individuală", "Индивидуальная", "Индивидуальный проект")
            .put("improved", "Îmbunătățită", "Planificare îmbunătățită", "Улучшенная", "Улучшенная планировка");

    public static final Vocabulary CONDITION = new Vocabulary("condition")
            .put("white_shell", "Variantă albă", "Белый вариант")
            .put("grey_shell", "Variantă sură", "Серый вариант")
            .put("euro_renovation", "Euroreparație", "Евроремонт")
            .put("cosmetic_renovation", "Reparație cosmetică", "Косметический ремонт")
            .put("individual_design", "Design individual", "Индивидуальный дизайн")
            .put("habitable", "Stare locativă", "Locuibilă", "Bună", "Жилое состояние")
            .put("no_renovation", "Fără reparație", "Без ремонта")
            .put("needs_renovation", "Are nevoie de reparație", "Necesită renovare", "Требует ремонта", "Нуждается в ремонте")
            .put("unfinished", "Construcție nefinisată", "Незавершённое строительство", "Незавершенное строительство")
            .put("commissioned", "Dat în exploatare", "Сдан в эксплуатацию")
            .put("to_demolish", "Casă de demolat", "Дом под снос");

    public static final Vocabulary PARKING = new Vocabulary("parking")
            .put("covered", "Sub acoperiș", "Под навесом", "Крытая")
            .put("garage", "Garaj", "Гараж")
            .put("underground", "Subterană", "Подземная", "Подземный паркинг")
            .put("open", "Deschis", "Deschisă", "Открытая", "Открытый", "Открытое");

    /** Types within the houses category. */
    public static final Vocabulary HOUSE_TYPE = new Vocabulary("property_type")
            .put("house", "Casă", "Дом", "Individual", "Individuală")
            .put("townhouse", "Townhouse", "Таунхаус")
            .put("duplex", "Duplex", "Дуплекс")
            .put("villa", "Vilă", "Вилла");
}
