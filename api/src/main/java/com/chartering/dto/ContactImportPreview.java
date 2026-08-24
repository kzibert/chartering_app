package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a contacts file would do to the database, before it does any of it.
 *
 * <p>Deliberately stateless: nothing is written when a file is uploaded, and no staging
 * table holds the parse between the two calls. The whole preview goes back to the browser,
 * the user edits it there, and the edited version is what {@code POST /contacts/import}
 * receives. A staging table would need an owner, an expiry and a migration, all to hold
 * data that is only ever a few hundred rows and is worthless the moment the tab closes.
 *
 * <p>Companies are a list of their own rather than a field on each person, because one bad
 * company name in an export is one bad name — not one per person who works there. The file
 * that prompted this had a company called "Soluciones tecnológicas que refuerzan tu
 * logística", a marketing tagline scraped in place of the name. Fixing that once and having
 * every person follow is the difference between a usable review screen and a chore.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContactImportPreview(
        String fileName,
        List<ImportCompany> companies,
        List<ImportPerson> people,
        /**
         * Trouble with the file as a whole rather than with any one row — columns nobody
         * recognised, rows with no name at all, a sheet with no header.
         */
        List<String> fileWarnings,
        ImportCounts counts) {

    /**
     * A company as the file describes it, matched against what is already stored.
     *
     * @param key           stable handle for this company within this preview; people point
     *                      at it by this rather than by name, so renaming one in the review
     *                      screen does not orphan everybody who works there
     * @param sourceName    the name exactly as the file spelled it, kept for the review
     *                      screen to show beside a name the user has since corrected
     * @param name          the name to actually use — editable
     * @param matchedId     an existing company to file everything under, or null to create
     * @param matchType     {@code exact}, {@code similar} or {@code new}; {@code similar} is
     *                      a suggestion the user is expected to look at, not a decision
     * @param contacts      addresses the file put on the organisation itself, plus any that
     *                      turned out to be shared by several of its people
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportCompany(
            String key,
            String sourceName,
            String name,
            Long matchedId,
            String matchedName,
            String matchType,
            String cityName,
            String country,
            String website,
            String notes,
            List<ImportContact> contacts,
            List<String> warnings) {
    }

    /**
     * @param companyKey which {@link ImportCompany} this person works for, by its key
     * @param matchedId  an existing person of that name at that company, or null to create
     * @param matchType  {@code exact} or {@code new}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportPerson(
            String key,
            String companyKey,
            String sourceName,
            String fullName,
            String title,
            String jobTitle,
            String greetingName,
            Long matchedId,
            String matchType,
            String notes,
            List<ImportContact> contacts,
            List<String> warnings) {
    }

    /**
     * One address or number off a row.
     *
     * @param duplicate this exact value is already on file against the same company, so
     *                  importing it would make a second copy. Reported rather than dropped:
     *                  the review screen unticks these by default and the user can say
     *                  otherwise.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ImportContact(
            String kind,
            String value,
            String label,
            boolean duplicate,
            String warning) {
    }

    /** The one-line summary above the review tables. */
    public record ImportCounts(
            int companiesNew,
            int companiesMatched,
            int peopleNew,
            int peopleMatched,
            int emails,
            int phones,
            int duplicates,
            int warnings) {
    }
}
