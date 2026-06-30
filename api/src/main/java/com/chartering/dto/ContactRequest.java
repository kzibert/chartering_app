package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ContactRequest {

    private Long personId;
    private Long companyId;

    @NotBlank(message = "contactKind is required")
    @Pattern(regexp = "email|phone", message = "contactKind must be 'email' or 'phone'")
    private String contactKind;

    @NotBlank(message = "contactValue is required")
    private String contactValue;

    private String notes;
}
