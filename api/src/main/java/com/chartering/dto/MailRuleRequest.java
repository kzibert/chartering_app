package com.chartering.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class MailRuleRequest {

    @NotBlank(message = "A rule needs a name.")
    @Size(max = 150)
    private String name;

    /** Where matching mail is filed. Required — a rule that files nowhere does nothing. */
    @NotNull(message = "Choose the folder this rule files into.")
    private Long folderId;

    private boolean enabled = true;

    /** Omit to leave an existing rule where it is, or to put a new one last. */
    private Integer sortOrder;

    /** ALL | ANY. Defaults to ALL, which is what "and also" reads as. */
    private String matchType = "ALL";

    private boolean markRead = false;

    /** At least one is required: a rule with no conditions would match every message. */
    @Valid
    private List<MailRuleConditionRequest> conditions = new ArrayList<>();
}
