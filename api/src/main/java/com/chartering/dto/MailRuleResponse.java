package com.chartering.dto;

import java.util.List;

public record MailRuleResponse(
        Long id,
        String name,
        Long folderId,
        String folderName,
        boolean enabled,
        /** Evaluation order, lowest first. The first rule that matches claims the message. */
        int sortOrder,
        /** ALL = every condition must match, ANY = at least one. */
        String matchType,
        boolean markRead,
        List<MailRuleConditionResponse> conditions) {
}
