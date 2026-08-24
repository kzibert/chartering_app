package com.chartering.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The preview as the user left it, sent back to be written.
 *
 * <p>Shaped like {@link ContactImportPreview} minus everything the server worked out for
 * itself — match types, warnings, duplicate flags. Those were advice for the review screen;
 * echoing them back would invite the writer to trust a client's opinion about what is
 * already in the database. What the client does decide is what survived the review: rows it
 * chose not to import are simply absent, so there is no "included" flag to honour or forget.
 *
 * @param companies every company the rows refer to, keyed as the preview keyed them
 * @param people    the people to create or add addresses to, each naming its company's key
 */
public record ContactImportRequest(
        @Valid List<ImportCompanyRequest> companies,
        @Valid List<ImportPersonRequest> people) {

    /**
     * @param matchedId an existing company to use; null creates one named {@link #name}
     */
    public record ImportCompanyRequest(
            @NotBlank(message = "company key is required") String key,
            @NotBlank(message = "company name is required")
            @Size(max = 255, message = "company name must be at most 255 characters") String name,
            Long matchedId,
            @Size(max = 255) String cityName,
            @Size(max = 100) String country,
            @Size(max = 255) String website,
            String notes,
            @Valid List<ImportContactRequest> contacts) {
    }

    /**
     * @param companyKey the key of the company in {@link #companies} this person belongs to.
     *                   Required: a person imported under no company is a row that lands on
     *                   no screen, which is the same shape {@code ContactService} refuses.
     * @param matchedId  an existing person to add these addresses to, rather than a new one
     */
    public record ImportPersonRequest(
            @NotBlank(message = "person key is required") String key,
            @NotBlank(message = "companyKey is required") String companyKey,
            @NotBlank(message = "fullName is required") String fullName,
            @Size(max = 20) String title,
            @Size(max = 120, message = "jobTitle must be at most 120 characters") String jobTitle,
            @Size(max = 120) String greetingName,
            Long matchedId,
            String notes,
            @Valid List<ImportContactRequest> contacts) {
    }

    public record ImportContactRequest(
            @NotNull @Pattern(regexp = "email|phone", message = "kind must be 'email' or 'phone'")
            String kind,
            @NotBlank(message = "contact value is required") String value,
            @Size(max = 20, message = "label must be at most 20 characters") String label) {
    }
}
