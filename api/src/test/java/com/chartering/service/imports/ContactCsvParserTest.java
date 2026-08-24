package com.chartering.service.imports;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parser against the shapes a real contacts export actually contains. Every case here
 * is taken from the file that prompted the feature rather than invented — the interesting
 * failures in this code are all failures to survive somebody else's formatting.
 */
class ContactCsvParserTest {

    private final ContactCsvParser parser = new ContactCsvParser();

    @Test
    void splitsALabelledPhoneCellIntoOneContactPerNumber() {
        List<ParsedRow.ParsedContact> phones =
                ContactCsvParser.splitLabelledPhones("Work,+32.3.821.13.35,Mobile,+32.475.89.02.67", "");

        assertThat(phones).hasSize(2);
        assertThat(phones.get(0).label()).isEqualTo("Work");
        assertThat(phones.get(0).value()).isEqualTo("+32.3.821.13.35");
        assertThat(phones.get(1).label()).isEqualTo("Mobile");
        assertThat(phones.get(1).value()).isEqualTo("+32.475.89.02.67");
    }

    @Test
    void aLabelGovernsEveryNumberAfterItRatherThanJustTheNext() {
        // "Work,605 75 98 82,943 371 849" — one label, two numbers. Pairing tokens off two
        // at a time would read "943 371 849" as a label and lose it.
        List<ParsedRow.ParsedContact> phones =
                ContactCsvParser.splitLabelledPhones("Work,605 75 98 82,943 371 849", "");

        assertThat(phones).hasSize(2);
        assertThat(phones).allMatch(p -> "Work".equals(p.label()));
        assertThat(phones).extracting(ParsedRow.ParsedContact::value)
                .containsExactly("605 75 98 82", "943 371 849");
    }

    @Test
    void dropsFragmentsWithTooFewDigitsToBeANumber() {
        // The Turkish row writes a range as "+90 216 532 30 00-01"; the CSV escaping leaves
        // the trailing "01" as a token of its own on some exports.
        List<ParsedRow.ParsedContact> phones =
                ContactCsvParser.splitLabelledPhones("Work,+90 216 532 30 00,01,+90 532 267 23 97", "");

        assertThat(phones).extracting(ParsedRow.ParsedContact::value)
                .containsExactly("+90 216 532 30 00", "+90 532 267 23 97");
    }

    @Test
    void aColumnThatNamesItsOwnKindLabelsWhatIsInIt() {
        assertThat(ContactCsvParser.splitLabelledPhones("+44 20 7123 4567", "Mobile"))
                .singleElement()
                .satisfies(p -> assertThat(p.label()).isEqualTo("Mobile"));
    }

    @Test
    void separatesAnEmailAndAHostTypedIntoOneWebsiteCell() {
        assertThat(ContactCsvParser.splitHostsAndEmails("info@caspianlines.com/www.caspianlines.com"))
                .containsExactly("info@caspianlines.com", "www.caspianlines.com");
    }

    @Test
    void leavesARealUrlPathAlone() {
        assertThat(ContactCsvParser.splitHostsAndEmails("https://fednav.com/about/fleet"))
                .containsExactly("fednav.com/about/fleet");
    }

    @Test
    void readsOrganisationAndPersonRowsFromOneSheet() throws IOException {
        String csv = """
                "Type","Name","First Name","Last Name","Job Title","Organization","Email","Phone Number","City","Country","Tags","Website"
                "Organization","FEDNAV","","","","FEDNAV","","","","","",""
                "Person","Tom Cardon","Tom","Cardon","Senior Manager","FEDNAV","falline.commercial@fednav.com","Work,+32.3.821.13.35,Mobile,+32.475.89.02.67","Antwerp","Belgium","BBEU26","fednav.com"
                """;

        List<ParsedRow> rows = parse(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).organisation).isTrue();
        assertThat(rows.get(0).organisationName).isEqualTo("FEDNAV");

        ParsedRow person = rows.get(1);
        assertThat(person.organisation).isFalse();
        assertThat(person.fullName).isEqualTo("Tom Cardon");
        assertThat(person.firstName).isEqualTo("Tom");
        assertThat(person.jobTitle).isEqualTo("Senior Manager");
        assertThat(person.organisationName).isEqualTo("FEDNAV");
        assertThat(person.cityName).isEqualTo("Antwerp");
        assertThat(person.country).isEqualTo("Belgium");
        assertThat(person.website).isEqualTo("fednav.com");
        assertThat(person.tags).isEqualTo("BBEU26");
        assertThat(person.contacts).extracting(ParsedRow.ParsedContact::value)
                .containsExactly("falline.commercial@fednav.com", "+32.3.821.13.35", "+32.475.89.02.67");
    }

    @Test
    void readsTheSameAddressFromEmailAndWorkEmailOnlyOnce() throws IOException {
        String csv = """
                "Name","Organization","Email","Work Email"
                "Lander Tolosa","Adur","lander@adur.com","lander@adur.com"
                """;

        assertThat(parse(csv).get(0).contacts).hasSize(1);
    }

    @Test
    void fallsThroughToWhicheverAddressBlockTheExportFilledIn() throws IOException {
        // The plain City/Country columns are empty and the Office ones are not — the shape
        // one row of the real file has.
        String csv = """
                "Name","Organization","City","Country","Office City","Office Country"
                "Tom Cardon","FEDNAV","","","Antwerp","Belgium"
                """;

        ParsedRow row = parse(csv).get(0);
        assertThat(row.cityName).isEqualTo("Antwerp");
        assertThat(row.country).isEqualTo("Belgium");
    }

    @Test
    void matchesHeadingsWhateverTheExporterSpellsThem() throws IOException {
        String csv = """
                "Full Name","Company","E-mail 1 - Value","Mobile Phone"
                "Nedim Kasar","CASPIANLINES","nedim@caspianlines.com","+90 532 267 23 97"
                """;

        ParsedRow row = parse(csv).get(0);
        assertThat(row.fullName).isEqualTo("Nedim Kasar");
        assertThat(row.organisationName).isEqualTo("CASPIANLINES");
        assertThat(row.contacts).extracting(ParsedRow.ParsedContact::kind, ParsedRow.ParsedContact::label)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("email", null),
                        org.assertj.core.groups.Tuple.tuple("phone", "Mobile"));
    }

    @Test
    void treatsARowWithNoTypeColumnAsAPersonWhenItHasAPersonsName() throws IOException {
        String csv = """
                "Name","Organization","Email"
                "FEDNAV","FEDNAV","ops@fednav.com"
                "Tom Cardon","FEDNAV","tom@fednav.com"
                """;

        List<ParsedRow> rows = parse(csv);
        assertThat(rows.get(0).organisation).isTrue();
        assertThat(rows.get(1).organisation).isFalse();
    }

    private List<ParsedRow> parse(String csv) throws IOException {
        return parser.parse(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))).rows();
    }
}
