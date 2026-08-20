package com.chartering.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class MailRuleConditionRequest {

    /** FROM | FROM_DOMAIN | TO | SUBJECT | BODY | ANY — parsed against the enum, case-insensitively. */
    @NotBlank(message = "Each condition needs a field to test.")
    private String field;

    /** CONTAINS | NOT_CONTAINS | EQUALS | STARTS_WITH | ENDS_WITH. Defaults to CONTAINS. */
    private String operator = "CONTAINS";

    @NotBlank(message = "Each condition needs something to look for.")
    private String value;
}
