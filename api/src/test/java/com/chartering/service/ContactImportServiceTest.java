package com.chartering.service;

import com.chartering.dto.ContactImportPreview;
import com.chartering.dto.ContactImportPreview.ImportCompany;
import com.chartering.dto.ContactImportPreview.ImportContact;
import com.chartering.dto.ContactImportPreview.ImportPerson;
import com.chartering.model.Company;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.repository.PersonRepository;
import com.chartering.service.imports.ContactCsvParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The preview's decisions, against the rows that made each decision necessary.
 *
 * <p>Reads from stubbed repositories rather than a database: everything under test here is
 * a judgement about the file — which rows are one company, which address belongs to a desk
 * rather than a person, which name is not a name — and none of it depends on JPA.
 */
class ContactImportServiceTest {

    /**
     * The shapes of the real export, trimmed to the columns that carry anything. Three
     * organisations, four people, one of the organisations named by its slogan, and one
     * mailbox given to two different managers.
     */
    private static final String CSV = """
            "Type","Name","First Name","Last Name","Job Title","Organization","Email","Work Email","Phone Number","City","Country","Tags","Website"
            "Organization","Soluciones tecnológicas que refuerzan tu logística","","","","Soluciones tecnológicas que refuerzan tu logística","","","","","","",""
            "Organization","FEDNAV","","","","FEDNAV","","","","","","",""
            "Organization","CASPIANLINES Lojistik Hizmetleri Tic. Ltd. Şti.","","","","CASPIANLINES Lojistik Hizmetleri Tic. Ltd. Şti.","","","","","","",""
            "Person","Lander Tolosa","Lander","Tolosa","Chief Project Officer (CPO)","Soluciones tecnológicas que refuerzan tu logística","lander@adur.com","lander@adur.com","Work,605 75 98 82,943 371 849","","Spain","BBEU26",""
            "Person","Tom Cardon","Tom","Cardon","Senior Manager","FEDNAV","falline.commercial@fednav.com","falline.commercial@fednav.com","Work,+32.3.821.13.35,Mobile,+32.475.89.02.67","Antwerp","Belgium","BBEU26","fednav.com"
            "Person","Thomas Deckers","Thomas","Deckers","Assistant Manager","FEDNAV","falline.commercial@fednav.com","falline.commercial@fednav.com","Work,+32.3.821.13.31,Mobile,+32.471.735.126","Antwerp","Belgium","BBEU26","fednav.com"
            "Person","Nedim Kasar","Nedim","Kasar","Port Master","CASPIANLINES Lojistik Hizmetleri Tic. Ltd. Şti.","","","Work,+90 216 532 30 00,+90 532 267 23 97","İstanbul","","BBEU26","info@caspianlines.com/www.caspianlines.com"
            """;

    private CompanyRepository companyRepository;
    private ContactImportService service;

    @BeforeEach
    void setUp() {
        companyRepository = Mockito.mock(CompanyRepository.class);
        PersonRepository personRepository = Mockito.mock(PersonRepository.class);
        ContactRepository contactRepository = Mockito.mock(ContactRepository.class);
        Mockito.when(companyRepository.findByLowercaseNames(Mockito.any())).thenReturn(List.of());
        Mockito.when(companyRepository.findAll()).thenReturn(List.of());
        service = new ContactImportService(
                new ContactCsvParser(), companyRepository, personRepository, contactRepository);
    }

    @Test
    void groupsEveryRowUnderTheCompanyItNames() {
        ContactImportPreview preview = preview();

        assertThat(preview.companies()).extracting(ImportCompany::sourceName)
                .containsExactly(
                        "Soluciones tecnológicas que refuerzan tu logística",
                        "FEDNAV",
                        "CASPIANLINES Lojistik Hizmetleri Tic. Ltd. Şti.");
        assertThat(preview.people()).extracting(ImportPerson::fullName)
                .containsExactly("Lander Tolosa", "Tom Cardon", "Thomas Deckers", "Nedim Kasar");
        assertThat(preview.counts().companiesNew()).isEqualTo(3);
        assertThat(preview.counts().peopleNew()).isEqualTo(4);
    }

    @Test
    void anAddressGivenToTwoPeopleBecomesTheCompanysOwn() {
        ContactImportPreview preview = preview();

        ImportCompany fednav = company(preview, "FEDNAV");
        assertThat(fednav.contacts()).extracting(ImportContact::value)
                .containsExactly("falline.commercial@fednav.com");
        assertThat(fednav.contacts().get(0).warning()).contains("more than one person");

        // ...and neither manager keeps a copy of it, or the desk would be mailed twice.
        for (String name : List.of("Tom Cardon", "Thomas Deckers")) {
            assertThat(person(preview, name).contacts())
                    .extracting(ImportContact::value)
                    .doesNotContain("falline.commercial@fednav.com");
        }
    }

    @Test
    void keepsAnAddressOnThePersonWhenOnlyOneClaimsIt() {
        assertThat(person(preview(), "Lander Tolosa").contacts())
                .extracting(ImportContact::value)
                .contains("lander@adur.com");
    }

    @Test
    void keepsThePhoneLabelsTheFileWroteInline() {
        assertThat(person(preview(), "Tom Cardon").contacts())
                .extracting(ImportContact::kind, ImportContact::value, ImportContact::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("phone", "+32.3.821.13.35", "Work"),
                        org.assertj.core.groups.Tuple.tuple("phone", "+32.475.89.02.67", "Mobile"));
    }

    @Test
    void flagsACompanyNameThatReadsAsASlogan() {
        assertThat(company(preview(), "Soluciones tecnológicas que refuerzan tu logística").warnings())
                .anySatisfy(w -> assertThat(w).contains("slogan"));
    }

    @Test
    void doesNotFlagALongButLegitimateCompanyName() {
        assertThat(ContactImportService.looksLikeATagline(
                "CASPIANLINES Lojistik Hizmetleri Tic. Ltd. Şti.")).isFalse();
        assertThat(ContactImportService.looksLikeATagline(
                "Soluciones tecnológicas que refuerzan tu logística")).isTrue();
    }

    @Test
    void recoversAnAddressFromAWebsiteCellThatHoldsBoth() {
        ImportCompany caspian = company(preview(), "CASPIANLINES Lojistik Hizmetleri Tic. Ltd. Şti.");

        assertThat(caspian.website()).isEqualTo("www.caspianlines.com");
        // The address was in the website column and belongs to nobody in particular, so it
        // reaches the person who carried that row rather than being thrown away with it.
        assertThat(person(preview(), "Nedim Kasar").contacts())
                .extracting(ImportContact::value)
                .contains("info@caspianlines.com");
    }

    @Test
    void hoistsCityAndCountryOffThePeopleRowsOntoTheCompany() {
        // The organisation rows in this export carry a name and nothing else; the address is
        // only ever on the people.
        ImportCompany fednav = company(preview(), "FEDNAV");
        assertThat(fednav.cityName()).isEqualTo("Antwerp");
        assertThat(fednav.country()).isEqualTo("Belgium");
        assertThat(fednav.website()).isEqualTo("fednav.com");
    }

    @Test
    void putsTheTagsColumnIntoNotes() {
        assertThat(person(preview(), "Tom Cardon").notes()).contains("BBEU26");
    }

    @Test
    void seedsTheGreetingFromTheFirstNameColumn() {
        assertThat(person(preview(), "Thomas Deckers").greetingName()).isEqualTo("Thomas");
    }

    @Test
    void suggestsButDoesNotAssumeACompanyThatDiffersOnlyByItsLegalForm() {
        Company existing = new Company();
        existing.setId(7L);
        existing.setName("Fednav Ltd.");
        Mockito.when(companyRepository.findAll()).thenReturn(List.of(existing));

        ImportCompany fednav = company(preview(), "FEDNAV");
        assertThat(fednav.matchType()).isEqualTo("similar");
        assertThat(fednav.matchedId()).isEqualTo(7L);
        assertThat(fednav.warnings()).anySatisfy(w -> assertThat(w).contains("Close but not identical"));
    }

    private ContactImportPreview preview() {
        return service.preview(new MockMultipartFile(
                "file", "contacts.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)));
    }

    private static ImportCompany company(ContactImportPreview preview, String sourceName) {
        return preview.companies().stream()
                .filter(c -> c.sourceName().equals(sourceName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no company " + sourceName));
    }

    private static ImportPerson person(ContactImportPreview preview, String fullName) {
        return preview.people().stream()
                .filter(p -> p.fullName().equals(fullName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no person " + fullName));
    }
}
