package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body for POST /api/v1/auth/change-password. */
@Data
public class ChangePasswordRequest {

    /** Asked for again even inside a valid session: a token left on an open laptop is not the password. */
    @NotBlank(message = "is required")
    private String currentPassword;

    @NotBlank(message = "is required")
    private String newPassword;
}
