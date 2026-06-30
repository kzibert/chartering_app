package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PersonRequest {

    @NotBlank(message = "fullName is required")
    private String fullName;

    private String title;
    private String greetingName;
    private Long companyId;
    private String notes;
}
