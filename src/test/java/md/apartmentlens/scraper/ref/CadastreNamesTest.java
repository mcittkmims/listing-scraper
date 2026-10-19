package md.apartmentlens.scraper.ref;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Full names as the cadastre writes them on geodata.gov.md. */
class CadastreNamesTest {

    @Test
    void aVillageInACommuneOfTheCapital() {
        var parts = CadastreNames.split("mun. Chișinău, com. Ciorescu, sat. Goian, str. Ichel");
        var l = CadastreNames.locality(parts);
        assertThat(l).isEqualTo(new CadastreNames.Locality("mun. Chișinău, com. Ciorescu, sat. Goian", "Goian", "sat",
                "Ciorescu", "Chișinău"));
        assertThat(CadastreNames.street(parts.getLast())).isEqualTo(new CadastreNames.Street("street", "Ichel"));
    }

    @Test
    void aTownInTheCapital() {
        var parts = CadastreNames.split("mun. Chișinău,  or. Durlești, str-la 5 Balcani");
        var l = CadastreNames.locality(parts);
        assertThat(l.name()).isEqualTo("Durlești");
        assertThat(l.kind()).isEqualTo("or");
        assertThat(l.fullName()).isEqualTo("mun. Chișinău, or. Durlești");
        assertThat(CadastreNames.street(parts.getLast())).isEqualTo(new CadastreNames.Street("lane", "5 Balcani"));
    }

    @Test
    void theCapitalItselfAndARaion() {
        assertThat(CadastreNames.locality(CadastreNames.split("mun. Bălți, str. Codrenilor")).name()).isEqualTo("Bălți");
        var raion = CadastreNames.locality(CadastreNames.split("r-nul Ocnița, com. Calarașovca, sat. Berezovca, str. Independenței 47"));
        assertThat(raion.name()).isEqualTo("Berezovca");
        assertThat(raion.district()).isEqualTo("Ocnița");
        assertThat(raion.commune()).isEqualTo("Calarașovca");
        var uta = CadastreNames.locality(CadastreNames.split("UTA Găgăuzia, or. Vulcănești, str. Maiacovscogo 9"));
        assertThat(uta.name()).isEqualTo("Vulcănești");
        assertThat(uta.district()).isEqualTo("Găgăuzia");
    }

    @Test
    void streetTypes() {
        assertThat(CadastreNames.street("bd. Dacia").type()).isEqualTo("boulevard");
        assertThat(CadastreNames.street("șos. Hâncești").type()).isEqualTo("highway");
        assertThat(CadastreNames.street("fun. Teilor").type()).isEqualTo("dead_end");
        assertThat(CadastreNames.street("p-ța Marii Adunări Naționale").type()).isEqualTo("square");
    }

    @Test
    void roadsBetweenPlacesAreNotStreets() {
        assertThat(CadastreNames.street("dr. naț. R5. km 8")).isNull();
        assertThat(CadastreNames.street("sat. Goian")).isNull();
    }
}
