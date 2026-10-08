package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Body for POST /api/v1/admin/tenants. A desk is created with its first administrator in the
 * same call: a desk nobody can log in to is a row nobody can do anything with.
 */
@Data
public class TenantCreateRequest {

    @NotBlank(message = "is required")
    @Size(max = 200, message = "must be at most 200 characters")
    private String name;

    @NotBlank(message = "is required")
    @Size(min = 3, max = 150, message = "must be 3 to 150 characters")
    private String adminUsername;

    @Size(max = 200, message = "must be at most 200 characters")
    private String adminDisplayName;

    /** Optional; blank means generated and returned once. */
    private String adminPassword;
}
