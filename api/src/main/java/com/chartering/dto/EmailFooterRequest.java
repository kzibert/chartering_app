package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailFooterRequest {

    @NotBlank(message = "footer name is required")
    @Size(max = 150, message = "name must be at most 150 characters")
    private String name;

    @NotBlank(message = "footer html is required")
    private String html;

    /** Setting this clears the flag on whichever footer held it before. */
    private boolean defaultFooter;
}
