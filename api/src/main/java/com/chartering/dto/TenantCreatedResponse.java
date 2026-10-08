package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A new desk and its first administrator, whose one-time password is shown this once. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantCreatedResponse(TenantResponse tenant, UserPasswordResponse admin) {
}
