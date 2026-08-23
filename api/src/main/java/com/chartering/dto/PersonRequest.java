package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PersonRequest {

    @NotBlank(message = "fullName is required")
    private String fullName;

    /** Honorific (Mr./Capt.), not the job — see {@link #jobTitle}. */
    private String title;

    /**
     * The position held at the company: "Chartering Manager", "Operations". Optional, and
     * separate from {@link #title} in every direction — neither implies the other.
     */
    @Size(max = 120, message = "jobTitle must be at most 120 characters")
    private String jobTitle;

    private String greetingName;
    private Long companyId;
    private String notes;
}
