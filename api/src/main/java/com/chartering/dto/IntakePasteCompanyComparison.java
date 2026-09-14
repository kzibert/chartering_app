package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A pasted company set against the one on file it was matched to: what the text says that the
 * record does not, so a person can tick what to update.
 *
 * <p>{@code people} and {@code contacts} are index-aligned with the request's own lists — row
 * three here is about the third person sent — because the screen is already holding those rows
 * as it edits them, and a second identity for the same row would be a second thing to keep in
 * step.
 *
 * @param fields       only the fields where the text gives something different from the record;
 *                     identical and blank ones are left out, since there is nothing to decide
 * @param peopleOnFile everybody on the company, for matching a parsed name to a person by hand
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakePasteCompanyComparison(Long companyId,
                                           String companyName,
                                           List<FieldRow> fields,
                                           List<PersonOnFile> peopleOnFile,
                                           List<PersonRow> people,
                                           List<ContactRow> contacts) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldRow(String field, String label, String current, String parsed) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonOnFile(Long personId, String fullName, String title, String jobTitle) {
    }

    /**
     * @param existingPersonId the person on file this one appears to be, or absent
     * @param matchedBy        {@code name} for the same full name, {@code surname} for the same
     *                         surname and first initial — the second is a suggestion and says so
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonRow(Long existingPersonId, String matchedBy) {
    }

    /**
     * @param existingContactId the contact on file with this address or number, or absent
     * @param currentLabel      its label on file, to show beside the one read
     * @param currentPersonName whose it is on file; absent for a company-wide one
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContactRow(Long existingContactId, String currentLabel, String currentPersonName) {
    }
}
