package com.chartering.service.parser;

import com.chartering.model.FeedItem;
import com.chartering.model.FeedSource;
import com.chartering.model.SourceKind;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint a discarded company question is suppressed by, and what an arrival off a
 * board says about itself.
 *
 * <p>Both are pure, and both are the half of this feature that has to be right for every shape
 * a signature can take — which is why they are tested without a database, a model server or a
 * Spring context anywhere in sight, the way {@code CompanyStyleReader}'s own reading is.
 */
class CompanyStyleIntakeTest {

    private static CompanyStyleReader.Style style(String name, String website,
                                                  List<String> people, List<String> emails) {
        return new CompanyStyleReader.Style(name, website, null, null, null,
                people.stream().map(p -> new CompanyStyleReader.Person(p, null, null)).toList(),
                emails.stream()
                        .map(e -> new CompanyStyleReader.ContactLine("email", e, null, null))
                        .toList());
    }

    // ---------------------------------------------------------------- the fingerprint

    @Test
    void theSameBlockWithItsLinesSwappedIsTheSameReading() {
        // A signature is text a person retypes, and two brokers at one firm sign in different
        // orders. A fingerprint that said these were different readings would be no
        // suppression at all: the discarded question would come back on the next circular.
        String a = CompanyStyleIntake.styleHash(style("CAMPANA", "campanashipping.com",
                List.of("Cenk Balci", "Tolga Okur"), List.of("chartering@campana.com", "fix@campana.com")));
        String b = CompanyStyleIntake.styleHash(style("CAMPANA", "campanashipping.com",
                List.of("Tolga Okur", "Cenk Balci"), List.of("fix@campana.com", "chartering@campana.com")));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void caseAndSpacingAreNotAChange() {
        String a = CompanyStyleIntake.styleHash(
                style("Campana Shipping", "campanashipping.com", List.of("Cenk Balci"),
                        List.of("Chartering@Campana.com")));
        String b = CompanyStyleIntake.styleHash(
                style("CAMPANA   SHIPPING", "campanashipping.com", List.of("cenk balci"),
                        List.of("chartering@campana.com")));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void anAddressTheBlockDidNotCarryBeforeIsANewReading() {
        // The other half: a discard has to stop being honoured the moment the signature
        // actually moves, or a firm that changed its desk address would never be asked about
        // again.
        String before = CompanyStyleIntake.styleHash(
                style("CAMPANA", "campanashipping.com", List.of("Cenk Balci"),
                        List.of("chartering@campana.com")));
        String after = CompanyStyleIntake.styleHash(
                style("CAMPANA", "campanashipping.com", List.of("Cenk Balci"),
                        List.of("chartering@campana.com", "ops@campana.com")));

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void aNewWebsiteIsANewReading() {
        String before = CompanyStyleIntake.styleHash(
                style("SLACO", null, List.of(), List.of("chartering@libship.ly")));
        String after = CompanyStyleIntake.styleHash(
                style("SLACO", "libship.ly", List.of(), List.of("chartering@libship.ly")));

        assertThat(before).isNotEqualTo(after);
    }

    // ---------------------------------------------------------------- the arrival

    @Test
    void aPostIsDatedByTheBoardRatherThanByTheFetch() {
        // A board is a standing page: the entry read this morning was posted on the 14th and
        // says SPOT. Dating it now would make a three-day-old position look like today's, and
        // staleness is the first thing Open Fleet has to show.
        FeedItem post = post(LocalDateTime.of(2026, 9, 14, 0, 0));

        Arrival arrival = Arrival.of(post, null, null, null);

        assertThat(arrival.reportedAt().toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026, 9, 14));
        assertThat(arrival.kind()).isEqualTo(SourceKind.WEB);
        assertThat(arrival.isMail()).isFalse();
    }

    @Test
    void aPostWithNoDateLineFallsBackToWhenItWasFetched() {
        FeedItem post = post(null);
        post.setFetchedAt(LocalDateTime.of(2026, 9, 17, 9, 30));

        assertThat(Arrival.of(post, null, null, null).reportedAt().toLocalDate())
                .isEqualTo(java.time.LocalDate.of(2026, 9, 17));
    }

    @Test
    void theArrivalIsNamedByTheFirmThatSignedIt() {
        // What the History tab prints as the cause of a change set. The board alone would say
        // where it was read and not who said it, which is the half a broker recognises.
        CompanyStyleIntake.Reading signature = new CompanyStyleIntake.Reading(
                style("PORT BULK CHARTERING", null, List.of(), List.of("fix@portbulk.example")),
                List.of(), null);

        Arrival arrival = Arrival.of(post(LocalDateTime.of(2026, 9, 16, 0, 0)), signature, null, null);

        assertThat(arrival.label()).isEqualTo("PORT BULK CHARTERING on ship.gr open ships");
        // Kept as text beside the (absent) link, for the reason a cargo source keeps one: a
        // sender with no contact row still has to be attributable, and on a board that is the
        // ordinary case rather than the exception.
        assertThat(arrival.fromAddress()).isEqualTo("fix@portbulk.example");
    }

    @Test
    void anUnsignedPostIsNamedByTheBoard() {
        Arrival arrival = Arrival.of(post(LocalDateTime.of(2026, 9, 16, 0, 0)), null, null, null);

        assertThat(arrival.label()).isEqualTo("ship.gr open ships");
    }

    private static FeedItem post(LocalDateTime publishedAt) {
        FeedSource source = new FeedSource();
        source.setId(1L);
        source.setName("ship.gr open ships");

        FeedItem post = new FeedItem();
        post.setId(7L);
        post.setSource(source);
        post.setPublishedAt(publishedAt);
        post.setText("M/V B LINE OPEN CASABLANCA 18-19.09");
        return post;
    }
}
