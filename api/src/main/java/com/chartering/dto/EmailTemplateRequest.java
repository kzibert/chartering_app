package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplateRequest {

    @NotBlank(message = "template name is required")
    @Size(max = 150, message = "name must be at most 150 characters")
    private String name;

    @Size(max = 300, message = "subject must be at most 300 characters")
    private String subject;

    @NotBlank(message = "template body is required")
    private String bodyHtml;
}
