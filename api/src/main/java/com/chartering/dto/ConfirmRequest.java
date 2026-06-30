package com.chartering.dto;

import lombok.Data;

/** Body for the PATCH /{id}/confirm endpoints. Both fields optional. */
@Data
public class ConfirmRequest {
    private String confirmedBy;
    private String confirmNotes;
}
