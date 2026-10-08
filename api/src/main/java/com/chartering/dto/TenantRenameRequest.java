package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for PUT /api/v1/admin/tenants/{id}. */
@Data
public class TenantRenameRequest {

    @NotBlank(message = "is required")
    @Size(max = 200, message = "must be at most 200 characters")
    private String name;
}
