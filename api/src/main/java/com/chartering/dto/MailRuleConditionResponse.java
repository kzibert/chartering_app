package com.chartering.dto;

public record MailRuleConditionResponse(
        Long id,
        /** FROM | FROM_DOMAIN | TO | SUBJECT | BODY | ANY */
        String field,
        /** CONTAINS | NOT_CONTAINS | EQUALS | STARTS_WITH | ENDS_WITH */
        String operator,
        String value) {
}
