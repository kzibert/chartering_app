package com.chartering.controller;

import com.chartering.dto.MailRuleRequest;
import com.chartering.dto.MailRuleResponse;
import com.chartering.dto.MailRuleRunResponse;
import com.chartering.service.MailRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/mailbox/rules")
@RequiredArgsConstructor
@Tag(name = "Mailbox rules", description = "Rules that file incoming mail into folders as it arrives")
public class MailRuleController {

    private final MailRuleService rules;

    @GetMapping
    @Operation(summary = "Every rule, in evaluation order",
            description = "Rules run lowest sortOrder first and the first match wins — a "
                    + "message lives in one folder, so no later rule is consulted.")
    public ResponseEntity<List<MailRuleResponse>> list() {
        return ResponseEntity.ok(rules.list());
    }

    @PostMapping
    @Operation(summary = "Create a rule",
            description = "Needs at least one condition: a rule with none would match every "
                    + "message and empty the Inbox into one folder. New rules go last.")
    public ResponseEntity<MailRuleResponse> create(@Valid @RequestBody MailRuleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rules.create(req));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a rule and its conditions",
            description = "The conditions in the body replace the rule's existing ones. Editing "
                    + "a rule does not re-file anything on its own — run /apply for that.")
    public ResponseEntity<MailRuleResponse> update(
            @PathVariable Long id, @Valid @RequestBody MailRuleRequest req) {
        return ResponseEntity.ok(rules.update(id, req));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a rule",
            description = "Mail it already filed stays where it is; only the \"filed by\" "
                    + "reference is cleared. Run /apply afterwards to return that mail to the "
                    + "Inbox if that is what you want.")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        rules.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/apply")
    @Operation(summary = "Run the rules over the mail already synced",
            description = "For after a rule is added or edited: rules normally run once, as "
                    + "mail arrives. Only mail in the Inbox or filed by a rule is touched — "
                    + "anything filed by hand is left exactly where it was put. Mail a rule "
                    + "had filed that no rule now claims goes back to the Inbox.")
    public ResponseEntity<MailRuleRunResponse> apply() {
        return ResponseEntity.ok(rules.applyToExisting());
    }
}
