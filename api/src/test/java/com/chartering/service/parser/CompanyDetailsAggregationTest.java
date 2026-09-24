package com.chartering.service.parser;

import com.chartering.dto.CompanyRequest;
import com.chartering.dto.IntakePasteCompanyComparison;
import com.chartering.dto.IntakePasteDraftResponse.CompanyDraft;
import com.chartering.dto.IntakePasteDraftResponse.ContactDraft;
import com.chartering.dto.IntakePasteDraftResponse.PersonDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One question per firm, built from every signature it has sent — and whether it needs the
 * queue at all.
 *
 * <p>Invented firms and {@code .example} addresses only: a test file is published with the
 * repository.
 */
class CompanyDetailsAggregationTest {

    private static CompanyRequest company(String name, String website, String city) {
        CompanyRequest c = new CompanyRequest();
        c.setName(name);
        c.setWebsite(website);
        c.setCityName(city);
        return c;
    }

    @Test
    void theDraftIsTheUnionOfEverySignatureNotTheNewest() {
        // The chartering desk signs with a mobile, operations with a direct line and another
        // person; the Friday list carries the office block only. The newest alone says least.
        CompanyDraft monday = new CompanyDraft(company("Oceanic Example Ltd", "oceanic.example", "Piraeus"),
                null,
                List.of(new PersonDraft("Anna Example", null, "Chartering Manager")),
                List.of(new ContactDraft("email", "chartering@oceanic.example", null, null),
                        new ContactDraft("phone", "+30 210 000 0001", "Mobile", "Anna Example")),
                List.of());
        CompanyDraft friday = new CompanyDraft(company("Oceanic Example Ltd", null, null),
                null,
                List.of(new PersonDraft("Anna Example", "Ms.", null),
                        new PersonDraft("Nikos Example", null, "Operations")),
                List.of(new ContactDraft("email", "CHARTERING@oceanic.example", null, null),
                        new ContactDraft("phone", "+30 210 000 0002", "Direct", "Nikos Example")),
                List.of());

        CompanyDraft merged = CompanyStyleIntake.mergeDrafts(monday, friday);

        assertThat(merged.company().getWebsite()).isEqualTo("oceanic.example");
        assertThat(merged.company().getCityName()).isEqualTo("Piraeus");
        assertThat(merged.people()).extracting(PersonDraft::fullName)
                .containsExactly("Anna Example", "Nikos Example");
        // The newer reading wins where it speaks and the older one fills where it does not.
        assertThat(merged.people().get(0).title()).isEqualTo("Ms.");
        assertThat(merged.people().get(0).jobTitle()).isEqualTo("Chartering Manager");
        // One address however it was written.
        assertThat(merged.contacts()).hasSize(3);
    }

    private static CompanyDraft draftWith(ContactDraft... contacts) {
        return new CompanyDraft(company("Oceanic Example Ltd", null, null), null, List.of(),
                List.of(contacts), List.of());
    }

    @Test
    void aMovedWebsiteAndANewPhoneAreMinor() {
        CompanyDraft draft = draftWith(new ContactDraft("phone", "+30 210 000 0003", null, null));
        IntakePasteCompanyComparison cmp = new IntakePasteCompanyComparison(5L, "Oceanic Example",
                List.of(new IntakePasteCompanyComparison.FieldRow("website", "Website", "old.example", "oceanic.example")),
                List.of(), List.of(),
                List.of(new IntakePasteCompanyComparison.ContactRow(null, null, null)));

        assertThat(CompanyStyleIntake.isMinor(5L, draft, cmp)).isTrue();
    }

    @Test
    void aNewEmailAddressIsNot() {
        CompanyDraft draft = draftWith(
                new ContactDraft("email", "desk@oceanic.example", null, null),
                new ContactDraft("email", "ops@oceanic.example", null, null));
        IntakePasteCompanyComparison cmp = new IntakePasteCompanyComparison(5L, "Oceanic Example",
                List.of(), List.of(), List.of(),
                List.of(new IntakePasteCompanyComparison.ContactRow(10L, null, null),
                        new IntakePasteCompanyComparison.ContactRow(null, null, null)));

        assertThat(CompanyStyleIntake.isMinor(5L, draft, cmp)).isFalse();
    }

    @Test
    void aLegalFormIsNotANewNameButAnotherNameIs() {
        CompanyDraft draft = draftWith();
        IntakePasteCompanyComparison sameFirm = new IntakePasteCompanyComparison(5L, "Oceanic Example",
                List.of(new IntakePasteCompanyComparison.FieldRow("name", "Name", "Oceanic Example", "OCEANIC EXAMPLE LTD.")),
                List.of(), List.of(), List.of());
        IntakePasteCompanyComparison renamed = new IntakePasteCompanyComparison(5L, "Oceanic Example",
                List.of(new IntakePasteCompanyComparison.FieldRow("name", "Name", "Oceanic Example", "Blue Example Shipping")),
                List.of(), List.of(), List.of());

        assertThat(CompanyStyleIntake.isMinor(5L, draft, sameFirm)).isTrue();
        assertThat(CompanyStyleIntake.isMinor(5L, draft, renamed)).isFalse();
    }

    @Test
    void aFirmNotOnFileIsNeverMinor() {
        assertThat(CompanyStyleIntake.isMinor(null, draftWith(), null)).isFalse();
    }
}
