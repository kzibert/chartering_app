package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body for POST /api/v1/auth/login. */
@Data
public class LoginRequest {

    @NotBlank(message = "is required")
    private String username;

    @NotBlank(message = "is required")
    private String password;
}
