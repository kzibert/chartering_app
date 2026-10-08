package com.chartering.dto;

import com.chartering.model.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for POST /api/v1/admin/users. */
@Data
public class UserCreateRequest {

    @NotBlank(message = "is required")
    @Size(min = 3, max = 150, message = "must be 3 to 150 characters")
    private String username;

    @Size(max = 200, message = "must be at most 200 characters")
    private String displayName;

    @NotNull(message = "is required")
    private UserRole role;

    /** Which desk. Only a platform administrator may name one; otherwise it is the caller's own. */
    private Long tenantId;

    /** Optional. Blank means the server generates one and returns it once. */
    private String password;
}
