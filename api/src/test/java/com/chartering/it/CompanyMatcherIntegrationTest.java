package com.chartering.it;

import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.repository.CompanyRepository;
import com.chartering.repository.ContactRepository;
import com.chartering.service.parser.CompanyMatcher;
import com.chartering.service.parser.CompanyStyleReader;
import com.chartering.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phone and similar-name halves of {@link CompanyMatcher}, which are compared in the database
 * rather than in Java. Every firm, person and number here is invented.
 *
 * <p>Not a unit test in {@code CompanyMatcherTest}: the point is the SQL, and a pure test cannot
 * run it. Each test works on its own firm names and numbers, so the rows the others leave behind
 * cannot make a result ambiguous.
 */
class CompanyMatcherIntegrationTest extends IntegrationTest {

    /** The desk the migrations seed (V30); the account these tests run as is not needed. */
    private static final long DESK = 1L;

    @Autowired
    private CompanyMatcher matcher;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ContactRepository contacts;

    @Test
    void aPhoneMatchesOnItsLastNineDigitsWhateverTheFormatting() {
        Company firm = firm("Phonetest " + tag() + " Chartering");
        phone(firm, "+30 210 555 0101");

        // The signature writes the same number as 00 and no spaces: the nine digits still agree.
        List<CompanyMatcher.Match> found = match(style(null, "00302105550101"));

        assertThat(found).extracting(CompanyMatcher.Match::companyId).contains(firm.getId());
        assertThat(found.stream().filter(m -> m.companyId().equals(firm.getId())).findFirst().orElseThrow().how())
                .isEqualTo("phone");
    }

    @Test
    void aNumberWithFewerThanNineDigitsMatchesNothing() {
        Company firm = firm("Shortnumber " + tag() + " Shipping");
        phone(firm, "2345678");

        // Seven digits on file, and a nine-digit number asked for: the file's digits can only ever
        // be a shorter string than any tail, so they must not agree by accident.
        List<CompanyMatcher.Match> found = match(style(null, "+30 2345678", "2345678"));

        assertThat(found).extracting(CompanyMatcher.Match::companyId).doesNotContain(firm.getId());
    }

    @Test
    void aSimilarNameIsStillFoundAndNotAnExactOne() {
        Company firm = firm("Halvorsen " + tag() + " Example Ltd");
        String stem = firm.getName().replace(" Ltd", "");

        // Same name, different legal form: exact match fails, resemblance finds it.
        List<CompanyMatcher.Match> found = match(style(stem + " A/S"));

        CompanyMatcher.Match hit = found.stream()
                .filter(m -> m.companyId().equals(firm.getId())).findFirst().orElseThrow();
        assertThat(hit.how()).isEqualTo("similar");
        assertThat(hit.reasons()).containsExactly("similar name");
        assertThat(hit.name()).isEqualTo(firm.getName());
    }

    // ------------------------------------------------------------------ helpers

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Company firm(String name) {
        return TenantContext.callAs(DESK, () -> {
            Company c = new Company();
            c.setName(name);
            return companies.save(c);
        });
    }

    private void phone(Company company, String number) {
        TenantContext.runAs(DESK, () -> {
            Contact c = new Contact();
            c.setCompany(company);
            c.setContactKind("phone");
            c.setContactValue(number);
            contacts.save(c);
        });
    }

    private List<CompanyMatcher.Match> match(CompanyStyleReader.Style style) {
        return TenantContext.callAs(DESK, () -> matcher.match(style));
    }

    /** A signature with a name and any number of phones, as the shape reader would hand over. */
    private static CompanyStyleReader.Style style(String name, String... phones) {
        List<CompanyStyleReader.ContactLine> lines = java.util.Arrays.stream(phones)
                .map(p -> new CompanyStyleReader.ContactLine("phone", p, null, null)).toList();
        return new CompanyStyleReader.Style(name, null, null, null, null, List.of(), lines);
    }
}
