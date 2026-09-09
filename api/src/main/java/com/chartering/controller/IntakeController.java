package com.chartering.controller;

import com.chartering.dto.ApplyLookupRequest;
import com.chartering.dto.CargoSourceResponse;
import com.chartering.dto.LinkSenderRequest;
import com.chartering.dto.IgnoreRequest;
import com.chartering.dto.IntakeItemResponse;
import com.chartering.dto.IntakeResolveRequest;
import com.chartering.dto.IntakeStatusResponse;
import com.chartering.dto.PageResponse;
import com.chartering.dto.ParsedEmailResponse;
import com.chartering.dto.ParserSettingsRequest;
import com.chartering.dto.ParserSettingsResponse;
import com.chartering.dto.SweepResponse;
import com.chartering.model.IntakeItemKind;
import com.chartering.model.IntakeItemStatus;
import com.chartering.model.ParseStatus;
import com.chartering.service.ParserSettings;
import com.chartering.service.parser.EmailParseRunner;
import com.chartering.service.parser.IntakeQueryService;
import com.chartering.service.parser.IntakeService;
import com.chartering.service.parser.ParserSweepService;
import com.chartering.service.lookup.VesselLookupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Intake: incoming mail read by the local model into cargoes and open positions.
 *
 * <p><b>A local-only feature, like the Analysis workbench.</b> The model is an HTTP endpoint
 * on a machine with a GPU — the sibling {@code chartering-ml} project — and the hosted
 * deployment can reach no such thing, so {@code PARSER_ENABLED} is false there and
 * everything here except {@code /status} answers 404. The status endpoint always answers,
 * because the UI asks it to decide whether the tab exists at all.
 *
 * <p><b>What lands without anybody looking, and what waits.</b> A position for a hull already
 * on file and a cargo nothing else looks like are written straight through — both only add,
 * and a wrong one is superseded or deleted. A particular that disagrees with the record, a
 * hull nobody has heard of, and a cargo that looks like one already in hand stop and become
 * items on this queue, because those are the readings that would overwrite something a
 * person put there.
 */
@RestController
@RequestMapping("/api/v1/intake")
@RequiredArgsConstructor
@Tag(name = "Intake",
        description = "Incoming mail read into cargoes and positions by the local model "
                + "(local deployments only)")
public class IntakeController {

    private final IntakeQueryService queries;
    private final IntakeService intake;
    private final ParserSweepService sweeps;
    private final EmailParseRunner runner;
    private final ParserSettings settings;
    private final VesselLookupService lookupService;

    // ------------------------------------------------------------------ status

    @GetMapping("/status")
    @Operation(summary = "Whether this deployment reads its mail with a model, and how that is going",
            description = "The one endpoint here that answers when the feature is off — it "
                    + "returns enabled=false and nothing else, which is what tells the UI to "
                    + "leave the tab out of the navigation rather than show one that errors "
                    + "when clicked. It also reaches the model server, so it is the slowest "
                    + "status call in the app; the connect timeout is short for that reason.")
    public ResponseEntity<IntakeStatusResponse> status() {
        return ResponseEntity.ok(queries.status());
    }

    // ------------------------------------------------------------------ sweeping

    @PostMapping("/sweep")
    @Operation(summary = "Read the mail now",
            description = "Returns immediately: a sweep is minutes of somebody else's GPU, so "
                    + "holding an HTTP request open for it would be its own bug report. Poll "
                    + "GET /sweep and watch `running`. Works whatever the interval is set to, "
                    + "including 0 — off means the timer does not run it, not that it cannot "
                    + "be run.")
    public ResponseEntity<SweepResponse> sweep() {
        sweeps.requestSweep();
        return ResponseEntity.accepted().body(currentSweep());
    }

    @GetMapping("/sweep")
    @Operation(summary = "Whether a sweep is running, and what the last one did")
    public ResponseEntity<SweepResponse> sweepStatus() {
        return ResponseEntity.ok(currentSweep());
    }

    private SweepResponse currentSweep() {
        ParserSweepService.SweepReport r = sweeps.lastReport();
        if (r == null) {
            return new SweepResponse(sweeps.isRunning(), null, null, null, null, null, null,
                    null, null, null);
        }
        return new SweepResponse(sweeps.isRunning(), r.read(), r.failed(), r.skipped(),
                r.positions(), r.cargoes(), r.items(), r.unreachable(), r.message(),
                r.finishedAt());
    }

    // ------------------------------------------------------------------ the queue

    @GetMapping("/items")
    @Operation(summary = "The review queue",
            description = "Oldest first by default, which is what a queue means. Filter by "
                    + "kind to work through one sort of question at a time — the three ask "
                    + "quite different things and answering thirty of one is faster than "
                    + "answering ten of each.")
    public ResponseEntity<PageResponse<IntakeItemResponse>> items(
            @Parameter(description = "NEW_VESSEL, VESSEL_FIELDS or CARGO_MERGE")
            @RequestParam(required = false) IntakeItemKind kind,
            @Parameter(description = "Defaults to PENDING — the queue. Pass ACCEPTED or "
                    + "REJECTED to read back what was decided.")
            @RequestParam(required = false, defaultValue = "PENDING") IntakeItemStatus status,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return ResponseEntity.ok(queries.search(kind, status, pageable));
    }

    @GetMapping("/items/{id}")
    @Operation(summary = "One item, with the whole reading behind it")
    public ResponseEntity<IntakeItemResponse> item(@PathVariable Long id) {
        return ResponseEntity.ok(queries.get(id));
    }

    @PostMapping("/items/{id}/resolve")
    @Operation(summary = "Answer one item",
            description = "ACCEPT does the proposed thing: create the vessel, write the "
                    + "ticked fields, merge the cargo. ALTERNATIVE does the other one — link "
                    + "the position to a vessel already on file, keep the cargo as a row "
                    + "of its own, or - on a VESSEL_FIELDS item - say that the matched hull "
                    + "is not this ship at all, which creates the vessel the email describes "
                    + "and withdraws the reading this email put on the other one — and it writes too, which is why it is not called a "
                    + "rejection. DISCARD writes nothing.\n\n"
                    + "On a VESSEL_FIELDS item, `fields` names which differences to accept; "
                    + "leaving it out accepts all of them.")
    public ResponseEntity<IntakeItemResponse> resolve(@PathVariable Long id,
                                                      @Valid @RequestBody IntakeResolveRequest req) {
        intake.resolve(id, req.getAction(), req.getFields(), req.getVesselId(), req.getNote(),
                currentUser());
        // Re-read rather than mapping what the write returned: accepting can change the row
        // it is about, and the screen should show what is now on file rather than what the
        // service was holding half way through.
        return ResponseEntity.ok(queries.get(id));
    }

    // ------------------------------------------------------------- the outside source

    @PostMapping("/items/{id}/lookup")
    @Operation(summary = "Look this hull up on the configured outside source, now",
            description = "Runs one search and replaces whatever was found before. Lookups "
                    + "also run unattended for items as they land in the queue; this is the "
                    + "button for the one you are looking at.\n\n"
                    + "Everything it returns is a proposal — nothing is written to a vessel "
                    + "until POST /items/{id}/apply-lookup names the fields to believe.")
    public ResponseEntity<IntakeItemResponse> lookup(@PathVariable Long id) {
        lookupService.lookUpNow(id);
        return ResponseEntity.ok(queries.get(id));
    }

    @PostMapping("/items/{id}/apply-lookup")
    @Operation(summary = "Write the ticked figures from the lookup onto the vessel",
            description = "A separate action from accepting the email's figures, and "
                    + "deliberately: each write is one change set naming its origin, so the "
                    + "vessel's History tab can say which values came off the web, from which "
                    + "source, on which day. Folded into the email accept it would save a "
                    + "click and lose exactly that.\n\n"
                    + "`vesselId` is needed only on a NEW_VESSEL item, where no record exists "
                    + "until the item has been accepted.")
    public ResponseEntity<IntakeItemResponse> applyLookup(
            @PathVariable Long id, @Valid @RequestBody ApplyLookupRequest req) {
        intake.applyLookup(id, req.getFields(), req.getVesselId());
        return ResponseEntity.ok(queries.get(id));
    }

    // ------------------------------------------------------------- the sender's company

    @PostMapping("/items/{id}/link-sender")
    @Operation(summary = "Attach the company that sent the email to this vessel",
            description = "A position list from a broker who is not the owner on file is the "
                    + "ordinary case, and that the broker works this hull is worth keeping — "
                    + "it is who to ring about her. The capacity is chosen rather than "
                    + "assumed: owner displaces the owner on the record, the two broker roles "
                    + "sit alongside it. Uses the same rule as the vessel's own screen, so a "
                    + "company appears once per hull.")
    public ResponseEntity<IntakeItemResponse> linkSender(
            @PathVariable Long id, @Valid @RequestBody LinkSenderRequest req) {
        intake.linkSenderCompany(id, req.getRole(), req.getNotes());
        return ResponseEntity.ok(queries.get(id));
    }

    @GetMapping("/lookup-fields")
    @Operation(summary = "The particulars an outside source may supply, and what to call them",
            description = "Deliberately short: an IMO, a deadweight, a build year, a flag and "
                    + "a type. Draft, capacities, gear and fittings are absent because the "
                    + "sources do not carry them in a form worth trusting — a tracking page's "
                    + "draught is the AIS-reported loaded figure, not a design maximum.")
    public ResponseEntity<Map<String, String>> lookupFields() {
        return ResponseEntity.ok(com.chartering.service.lookup.LookupFields.fields());
    }

    @GetMapping("/vessel-fields")
    @Operation(summary = "The vessel particulars an accept may name, and what to call them",
            description = "So the review table labels a field the same way the vessel's own "
                    + "edit form does, without the two lists being kept in step by hand.")
    public ResponseEntity<Map<String, String>> vesselFields() {
        return ResponseEntity.ok(queries.vesselFields());
    }

    // ------------------------------------------------------------------ the log

    @GetMapping("/parsed")
    @Operation(summary = "What the parser has read",
            description = "The question a review queue cannot answer: not what needs you, but "
                    + "whether this morning's mail was read at all and what was made of it. "
                    + "Filter by FAILED to find what the model could not manage.")
    public ResponseEntity<PageResponse<ParsedEmailResponse>> parsed(
            @Parameter(description = "PARSED, FAILED or SKIPPED")
            @RequestParam(required = false) ParseStatus status,
            @PageableDefault(size = 25) Pageable pageable) {
        return ResponseEntity.ok(queries.parsed(status, pageable));
    }

    @GetMapping("/parsed/{id}")
    @Operation(summary = "One parse, including the model's answer verbatim",
            description = "The raw answer is here and not in the list: it is what settles "
                    + "whether the model read the email wrong or we filed its answer wrong, "
                    + "and it is far too large to put on every row of a page.")
    public ResponseEntity<ParsedEmailResponse> parsedDetail(@PathVariable Long id) {
        return ResponseEntity.ok(queries.parsedDetail(id));
    }

    @PostMapping("/parsed/{mailMessageId}/reopen")
    @Operation(summary = "Queue a message to be read again",
            description = "Resets the attempt count on a failed row. A message that hit the "
                    + "retry ceiling while the workstation was switched off is not a message "
                    + "the model cannot read, and saying so should not mean editing the "
                    + "database by hand.")
    public ResponseEntity<Void> reopen(@PathVariable Long mailMessageId) {
        runner.reopen(mailMessageId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/parsed/{mailMessageId}/ignore")
    @Operation(summary = "Take a message out of the parser's hands for good",
            description = "The answer to a failure a person has looked at and does not want "
                    + "retried: the email that defeats the model every time, the forwarded "
                    + "thread with no position in it, the newsletter. Recorded rather than "
                    + "deleted - a row is what stops tomorrow's sweep finding the message "
                    + "again and spending another model call on it. Reversible: reopen puts "
                    + "it back in the queue.")
    public ResponseEntity<Void> ignoreParsed(@PathVariable Long mailMessageId,
                                             @org.springframework.web.bind.annotation.RequestBody(required = false)
                                             @Valid IgnoreRequest req) {
        runner.ignore(mailMessageId, req == null ? null : req.getNote());
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ cargo sources

    @GetMapping("/cargoes/{cargoId}/sources")
    @Operation(summary = "Everyone who has told us about this cargo",
            description = "What a merge is for: three brokers working one charterer's enquiry "
                    + "become one cargo, and this is what keeps 'who else is working this' "
                    + "answerable afterwards. Not behind the parser switch — a merged cargo's "
                    + "sources are part of the cargo, and hiding them on a deployment with "
                    + "the parser off would make it look like one somebody typed.")
    public ResponseEntity<java.util.List<CargoSourceResponse>> cargoSources(
            @PathVariable Long cargoId) {
        return ResponseEntity.ok(queries.cargoSources(cargoId));
    }

    // ------------------------------------------------------------------ settings

    @GetMapping("/settings")
    @Operation(summary = "How often the mailbox is read, and how much of it at a time")
    public ResponseEntity<ParserSettingsResponse> settings() {
        return ResponseEntity.ok(toResponse(settings.values()));
    }

    @PutMapping("/settings")
    @Operation(summary = "Change the sweep interval or batch size",
            description = "An interval of 0 turns the timer off; Parse now still works. A "
                    + "lookback of 0 removes the age limit, so a sweep will reach back through "
                    + "the whole mailbox. Every field is optional, so the form can send one "
                    + "without holding the others.")
    public ResponseEntity<ParserSettingsResponse> updateSettings(
            @Valid @RequestBody ParserSettingsRequest req) {
        return ResponseEntity.ok(toResponse(
                settings.update(req.getSweepIntervalMinutes(), req.getSweepBatchSize(),
                        req.getSweepMaxAgeDays())));
    }

    @DeleteMapping("/settings")
    @Operation(summary = "Back to the configured defaults")
    @ResponseStatus(HttpStatus.OK)
    public ResponseEntity<ParserSettingsResponse> resetSettings() {
        return ResponseEntity.ok(toResponse(settings.reset()));
    }

    private static ParserSettingsResponse toResponse(ParserSettings.Values v) {
        ParserSettings.Values defaults = ParserSettings.defaults();
        return new ParserSettingsResponse(
                v.sweepIntervalMinutes(), v.sweepBatchSize(), v.sweepMaxAgeDays(),
                defaults.sweepIntervalMinutes(), defaults.sweepBatchSize(),
                defaults.sweepMaxAgeDays());
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || !auth.isAuthenticated() ? null : auth.getName();
    }
}
