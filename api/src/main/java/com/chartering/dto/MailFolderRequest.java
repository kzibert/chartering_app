package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class MailFolderRequest {

    @NotBlank(message = "A folder name is required.")
    @Size(max = 100)
    private String name;

    private String notes;

    /** Position in the rail. Left at 0 the folder sorts by name among the others. */
    private int sortOrder = 0;
}
