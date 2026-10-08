package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An account just created or reset, with the password it was given.
 *
 * <p>The only time a password ever leaves the server, and only when the server chose it: it
 * is shown once to the administrator to hand over, and the account must replace it at the
 * first login. Absent when the administrator typed the password themselves - they know it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserPasswordResponse(UserResponse user, String temporaryPassword) {
}
