package com.chartering.service.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The model's reading of a signature, and the guard that keeps it honest.
 *
 * <p>Every firm, person, number and domain is invented ({@code .example}), as in
 * {@link CompanyStyleReaderTest}.
 */
class CompanyStyleReaderModelTest {

    private static final String TEXT = """
            Pls offer firm for 3000 mts wheat, Izmail / Marmara.

            Best regards,
            Jane Doe - Chartering Dept.
            EXAMPLE BULK SHIPPING S.A. / Istanbul
            Tel: +90 212 000 00 01   Mob: +90 530 000 00 03 (whatsapp)
            E-mail: chartering ( @ ) examplebulk ( . ) example
            www.examplebulk.example
            """;

    private static Extraction withCompany(Extraction.ExtractedCompany company) {
        return new Extraction("cargo_offer", List.of(), List.of(), null, company, null);
    }

    @Test
    void theModelsReadingIsUsedWhenItGaveOne() {
        Extraction e = withCompany(new Extraction.ExtractedCompany(
                "EXAMPLE BULK SHIPPING S.A.", "examplebulk.example", "Istanbul", "", "",
                List.of(new Extraction.ExtractedPerson("Jane Doe", "", "Chartering Dept.")),
                List.of(new Extraction.ExtractedContact("phone", "+90 212 000 00 01", "Work", ""),
                        new Extraction.ExtractedContact("phone", "+90 530 000 00 03", "Mobile", "Jane Doe"),
                        new Extraction.ExtractedContact("email", "chartering@examplebulk.example", "", ""))));

        CompanyStyleReader.Style s = CompanyStyleReader.readWithModel(TEXT, e);

        assertThat(s.name()).isEqualTo("EXAMPLE BULK SHIPPING S.A.");
        assertThat(s.city()).isEqualTo("Istanbul");
        // "" is "not in the block", which the rest of the app spells null.
        assertThat(s.country()).isNull();
        assertThat(s.people()).extracting(CompanyStyleReader.Person::fullName, CompanyStyleReader.Person::jobTitle)
                .containsExactly(tuple("Jane Doe", "Chartering Dept."));
        assertThat(s.contacts())
                .extracting(CompanyStyleReader.ContactLine::value, CompanyStyleReader.ContactLine::label,
                        CompanyStyleReader.ContactLine::personName)
                .containsExactly(
                        tuple("+90 212 000 00 01", "Work", null),
                        tuple("+90 530 000 00 03", "Mobile", "Jane Doe"),
                        // Read through the "( @ )" and "( . )" the broker wrote it with.
                        tuple("chartering@examplebulk.example", null, null));
    }

    @Test
    void aContactOrNameTheTextDoesNotHoldIsDropped() {
        Extraction e = withCompany(new Extraction.ExtractedCompany(
                "EXAMPLE BULK SHIPPING S.A.", "", "", "", "",
                List.of(new Extraction.ExtractedPerson("John Roe", "Mr.", "")),
                List.of(new Extraction.ExtractedContact("phone", "+90 530 999 99 99", "Mobile", "John Roe"),
                        new Extraction.ExtractedContact("email", "john.roe@examplebulk.example", "", "John Roe"),
                        new Extraction.ExtractedContact("phone", "+90 530 000 00 03", "Mobile", "John Roe"))));

        CompanyStyleReader.Style s = CompanyStyleReader.readWithModel(TEXT, e);

        assertThat(s.people()).isEmpty();
        // The number is real, so it stays - but the person it was filed under is not in the
        // text, so it no longer belongs to anybody.
        assertThat(s.contacts()).extracting(CompanyStyleReader.ContactLine::value, CompanyStyleReader.ContactLine::personName)
                .containsExactly(tuple("+90 530 000 00 03", null));
    }

    @Test
    void aLabelOutsideTheContactFormsFourWordsBecomesWork() {
        Extraction e = withCompany(new Extraction.ExtractedCompany(
                "", "", "", "", "", List.of(),
                List.of(new Extraction.ExtractedContact("phone", "+90 212 000 00 01", "Office", ""))));

        assertThat(CompanyStyleReader.readWithModel(TEXT, e).contacts())
                .extracting(CompanyStyleReader.ContactLine::label).containsExactly("Work");
    }

    @Test
    void anEmptyOrMissingCompanyFallsBackToTheShapeReader() {
        Extraction empty = withCompany(new Extraction.ExtractedCompany("", "", "", "", "", List.of(), List.of()));
        Extraction older = new Extraction("cargo_offer", List.of(), List.of(), null, null, null);

        CompanyStyleReader.Style fromEmpty = CompanyStyleReader.readWithModel(TEXT, empty);
        CompanyStyleReader.Style fromOlder = CompanyStyleReader.readWithModel(TEXT, older);
        CompanyStyleReader.Style shapes = CompanyStyleReader.read(TEXT, null);

        assertThat(fromEmpty).isEqualTo(shapes);
        assertThat(fromOlder).isEqualTo(shapes);
        assertThat(CompanyStyleReader.readWithModel(TEXT, null)).isEqualTo(shapes);
    }
}
