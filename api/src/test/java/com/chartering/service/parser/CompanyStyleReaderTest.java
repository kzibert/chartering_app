package com.chartering.service.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Plain JUnit: the reader is pure text in, records out.
 *
 * <p>The samples copy the <em>shapes</em> this mailbox's signatures take — the layout, the
 * labels, where the name sits — and nothing else. Every firm, person, address, number and
 * domain below is invented ({@code .example} is reserved for exactly this), so the tests carry
 * no correspondent's details into the repository.
 */
class CompanyStyleReaderTest {

    private static final String SIGNATURE = """
            Dear Sirs,

            Pls propose suitable cargoes for our below vessel.

            Best regards,

            Jane Doe
            Chartering Manager
            EXAMPLE BULK SHIPPING S.A.
            Harbour Street No:12
            34000 Karakoy, Istanbul / Turkey
            Tel: +90 212 000 00 01  Fax: +90 212 000 00 02
            Mob: +90 530 000 00 03
            E-mail: jane.doe@examplebulk.example | chartering@examplebulk.example
            www.examplebulk.example
            """;

    @Test
    void readsTheFirmItsPlaceAndItsWebsite() {
        CompanyStyleReader.Style s = CompanyStyleReader.read(SIGNATURE, null);

        assertThat(s.name()).isEqualTo("EXAMPLE BULK SHIPPING S.A.");
        assertThat(s.website()).isEqualTo("examplebulk.example");
        assertThat(s.country()).isEqualTo("Turkey");
        assertThat(s.city()).isEqualTo("Istanbul");
        assertThat(s.address()).contains("Harbour Street").contains("Istanbul");
    }

    @Test
    void readsThePersonAboveTheirJobAndFilesTheirOwnAddressUnderThem() {
        CompanyStyleReader.Style s = CompanyStyleReader.read(SIGNATURE, null);

        assertThat(s.people()).extracting(CompanyStyleReader.Person::fullName).containsExactly("Jane Doe");
        assertThat(s.people().get(0).jobTitle()).isEqualTo("Chartering Manager");
        assertThat(s.contacts()).filteredOn(c -> c.kind().equals("email"))
                .extracting(CompanyStyleReader.ContactLine::value, CompanyStyleReader.ContactLine::personName)
                .containsExactly(
                        tuple("jane.doe@examplebulk.example", "Jane Doe"),
                        // The desk address names nobody and stays company-wide.
                        tuple("chartering@examplebulk.example", null));
    }

    @Test
    void aLabelGovernsTheNumbersAfterItAndOnlyAMobileIsAPersonsOwn() {
        CompanyStyleReader.Style s = CompanyStyleReader.read(SIGNATURE, null);

        assertThat(s.contacts()).filteredOn(c -> c.kind().equals("phone"))
                .extracting(CompanyStyleReader.ContactLine::value, CompanyStyleReader.ContactLine::label,
                        CompanyStyleReader.ContactLine::personName)
                .containsExactly(
                        // Printed inside her signature, and still the firm's switchboard and fax.
                        tuple("+90 212 000 00 01", "Work", null),
                        tuple("+90 212 000 00 02", "Fax", null),
                        tuple("+90 530 000 00 03", "Mobile", "Jane Doe"));
    }

    /** A typed-out card: a name alone at the top, no job line, the firm behind a label. */
    @Test
    void readsATypedOutCardWithTheNameAloneAtTheTop() {
        String card = """
                John Smith

                Example Street 1, DK-9990 Skagen, Denmark
                Phone +45-00000001
                Mobile: +45-00000002
                E-Mail chartering@northsea.example

                Webpage: www.northsea.example
                Linkedin: North Sea Example Chartering A/S
                Bimco Membership No: 123456
                """;
        CompanyStyleReader.Style s = CompanyStyleReader.read(card, null);

        assertThat(s.name()).isEqualTo("North Sea Example Chartering A/S");
        assertThat(s.website()).isEqualTo("northsea.example");
        assertThat(s.country()).isEqualTo("Denmark");
        assertThat(s.city()).isEqualTo("Skagen");
        assertThat(s.people()).extracting(CompanyStyleReader.Person::fullName).containsExactly("John Smith");
        assertThat(s.contacts())
                .extracting(CompanyStyleReader.ContactLine::kind, CompanyStyleReader.ContactLine::value,
                        CompanyStyleReader.ContactLine::label, CompanyStyleReader.ContactLine::personName)
                .containsExactly(
                        tuple("email", "chartering@northsea.example", null, null),
                        // The office line is the firm's; the mobile is the one person's.
                        tuple("phone", "+45-00000001", "Work", null),
                        tuple("phone", "+45-00000002", "Mobile", "John Smith"));
    }

    @Test
    void anImoOrADateIsNotAPhoneNumber() {
        String text = """
                MV EXAMPLE STAR  IMO: 9999999  DWCC 15.700
                OPEN SPOT AVEIRO 15.09.2026
                """;
        CompanyStyleReader.Style s = CompanyStyleReader.read(text, null);

        assertThat(s.contacts()).isEmpty();
        assertThat(s.people()).isEmpty();
    }

    @Test
    void theModelsReadingOfTheSignerWinsOverTheLines() {
        CompanyStyleReader.Style s = CompanyStyleReader.read(
                "regards\nchartering@harbourline.example",
                new Extraction.Broker("HARBOUR LINE EXAMPLE CORP", "Alex Brown", "chartering@harbourline.example"));

        assertThat(s.name()).isEqualTo("HARBOUR LINE EXAMPLE CORP");
        assertThat(s.people()).extracting(CompanyStyleReader.Person::fullName).containsExactly("Alex Brown");
        assertThat(s.website()).isEqualTo("harbourline.example");
    }

    @Test
    void aWebmailDomainIsNeverTakenForTheWebsite() {
        CompanyStyleReader.Style s = CompanyStyleReader.read("John Smith\njohn.smith.example@gmail.com", null);

        assertThat(s.website()).isNull();
    }
}
