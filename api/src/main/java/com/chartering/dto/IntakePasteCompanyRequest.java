package com.chartering.dto;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * A pasted company style, accepted as the reviewer left it — or, with only {@code companyId}
 * and the parsed parts filled, the question "how does this compare with what is on file".
 *
 * <p>Exactly one of {@code companyId} and {@code company} on an accept. One request rather
 * than a create followed by a dozen contact calls, because the parts only make sense together —
 * a firm created and then left without the addresses it was created for is the half-finished
 * record the review screen exists to avoid.
 *
 * <p><b>Nothing on file changes unless it was ticked.</b> The text is a signature, not a
 * source of record, so every overwrite of a company on file is sent explicitly: a field in
 * {@code companyChanges}, an {@code existingPersonId} on a person, an {@code existingContactId}
 * on a contact. Anything else only adds.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntakePasteCompanyRequest {

    /** A company on file to add to, and to update where ticked. */
    private Long companyId;

    /**
     * A new company — or, when comparing against one on file, the company as the text gave
     * it. Ignored on an accept when {@code companyId} is given; {@code companyChanges} is how
     * a company on file is changed.
     */
    @Valid
    private CompanyRequest company;

    /** Fields to overwrite on the company on file. Null leaves a field alone. */
    private CompanyChanges companyChanges;

    private List<PersonChange> people = new ArrayList<>();

    private List<ContactChange> contacts = new ArrayList<>();

    /**
     * @param notes added beneath the notes already there rather than replacing them — an
     *              address read out of a signature is worth having and not worth losing
     *              whatever somebody wrote about the firm to make room for it
     */
    public record CompanyChanges(String name, String cityName, String country, String website, String notes) {
    }

    /**
     * @param existingPersonId the person on file this is — their name, title and job title
     *                         become these (blank leaves one alone); null creates a new person,
     *                         unless one of that exact name is already on the company
     */
    public record PersonChange(String fullName, String title, String jobTitle, Long existingPersonId) {
    }

    /**
     * @param personName        which person, by the fullName used in {@code people}; blank is a
     *                          company-wide address
     * @param existingContactId the contact on file this is — its label and whose it is become
     *                          these; null adds it, unless the company already has it
     */
    public record ContactChange(String kind, String value, String label, String personName,
                                Long existingContactId) {
    }
}
