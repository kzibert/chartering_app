package com.chartering.dto;

import com.chartering.model.UserRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Body for PUT /api/v1/admin/users/{id}. The username is not editable: it is on years of history. */
@Data
public class UserUpdateRequest {

    @Size(max = 200, message = "must be at most 200 characters")
    private String displayName;

    @NotNull(message = "is required")
    private UserRole role;
}
