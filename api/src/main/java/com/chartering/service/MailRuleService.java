package com.chartering.service;

import com.chartering.dto.MailRuleConditionRequest;
import com.chartering.dto.MailRuleRequest;
import com.chartering.dto.MailRuleResponse;
import com.chartering.dto.MailRuleRunResponse;
import com.chartering.exception.ResourceNotFoundException;
import com.chartering.mapper.DtoMapper;
import com.chartering.model.MailFolder;
import com.chartering.model.MailMessage;
import com.chartering.model.MailRule;
import com.chartering.model.MailRuleCondition;
import com.chartering.repository.MailFolderRepository;
import com.chartering.repository.MailMessageRepository;
import com.chartering.repository.MailRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The filing rules: what they are, and what they do to a message.
 *
 * <h2>Which mail a rule may move</h2>
 * <p>Rules claim a message only when it is in the Inbox, or when a rule put it where it is.
 * Mail filed <em>by hand</em> is never touched, however well a rule matches it. That line
 * matters more than it looks: without it, correcting a mis-filed message would last exactly
 * until the next sync, and the user would be arguing with the app instead of using it.
 *
 * <p>The first matching rule wins and evaluation stops. A message lives in one folder, so
 * "every matching rule applies" would in practice mean "the last one applies", while reading
 * as if it meant something richer.
 *
 * <h2>Why rules run against stored rows</h2>
 * <p>Nothing here reads IMAP. A rule is evaluated against a {@link MailMessage} that is
 * already in the database, which is what makes "Apply rules now" possible: a rule written
 * this morning can be run over mail that arrived last month, and a rule that turns out to be
 * wrong can be fixed and re-run rather than leaving a permanent mess behind it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MailRuleService {

    /** Bulk re-filing reads the mail in pages rather than all of it at once. */
    private static final int RUN_PAGE_SIZE = 500;

    private final MailRuleRepository rules;
    private final MailFolderRepository folders;
    private final MailMessageRepository messages;
    private final DtoMapper mapper;

    // ---------------------------------------------------------------- reading

    @Transactional(readOnly = true)
    public List<MailRuleResponse> list() {
        return rules.findAllForDisplay().stream().map(mapper::toMailRuleResponse).toList();
    }

    /** Enabled rules in evaluation order, conditions loaded. Read once per sync batch. */
    @Transactional(readOnly = true)
    public List<MailRule> enabledRules() {
        return rules.findEnabledForEvaluation();
    }

    // ---------------------------------------------------------------- writing

    @Transactional
    public MailRuleResponse create(MailRuleRequest req) {
        MailRule rule = new MailRule();
        // New rules go last, so adding one cannot silently outrank the rules already there.
        rule.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : rules.maxSortOrder() + 10);
        apply(rule, req);
        return mapper.toMailRuleResponse(rules.save(rule));
    }

    @Transactional
    public MailRuleResponse update(Long id, MailRuleRequest req) {
        MailRule rule = rules.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mail rule", id));
        if (req.getSortOrder() != null) {
            rule.setSortOrder(req.getSortOrder());
        }
        apply(rule, req);
        return mapper.toMailRuleResponse(rules.save(rule));
    }

    /**
     * Deletes the rule. Mail it had already filed stays where it is — the filing happened,
     * and moving a hundred messages back to the Inbox because somebody tidied up a rule
     * would be a bulk change nobody asked for. Only the "filed by" fingerprint is cleared,
     * since the rule it names no longer exists.
     */
    @Transactional
    public void delete(Long id) {
        if (!rules.existsById(id)) {
            throw new ResourceNotFoundException("Mail rule", id);
        }
        messages.clearRuleReference(id);
        rules.deleteById(id);
    }

    private void apply(MailRule rule, MailRuleRequest req) {
        MailFolder folder = folders.findById(req.getFolderId())
                .orElseThrow(() -> new ResourceNotFoundException("Mail folder", req.getFolderId()));

        String name = req.getName().trim();
        rules.findByNameIgnoringCase(name)
                .filter(other -> !other.getId().equals(rule.getId()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("A rule called \"" + name + "\" already exists.");
                });
        if (req.getConditions() == null || req.getConditions().isEmpty()) {
            // Not pedantry: a rule with no conditions matches every message, so it would
            // empty the Inbox into one folder the moment it was saved.
            throw new IllegalArgumentException("A rule needs at least one condition.");
        }

        rule.setName(name);
        rule.setFolder(folder);
        rule.setEnabled(req.isEnabled());
        rule.setMarkRead(req.isMarkRead());
        rule.setMatchType(parseMatchType(req.getMatchType()));

        rule.getConditions().clear();
        for (MailRuleConditionRequest c : req.getConditions()) {
            MailRuleCondition condition = new MailRuleCondition();
            condition.setField(parseField(c.getField()));
            condition.setOperator(parseOperator(c.getOperator()));
            if (c.getValue() == null || c.getValue().isBlank()) {
                throw new IllegalArgumentException("Each condition needs something to look for.");
            }
            condition.setValue(c.getValue().trim());
            rule.addCondition(condition);
        }
    }

    // ---------------------------------------------------------------- evaluating

    /**
     * Files one message according to the rules, if any of them claims it.
     *
     * <p>Called both during a sync, for a message that has just arrived, and by
     * {@link #applyToExisting()} for mail already stored. Returns what changed so callers can
     * report it; the caller owns the transaction and the save.
     */
    public Outcome fileByRules(MailMessage m, List<MailRule> ordered) {
        if (!ruleManaged(m)) {
            return Outcome.UNTOUCHED;
        }
        Optional<MailRule> match = ordered.stream().filter(r -> matches(r, m)).findFirst();

        if (match.isEmpty()) {
            // No rule claims it any more. If a rule had filed it, the rule has since been
            // changed, disabled or deleted, so it goes back to the Inbox: the alternative is
            // mail sitting in a folder that nothing explains.
            if (m.getFolder() == null) {
                return Outcome.UNTOUCHED;
            }
            m.setFolder(null);
            m.setFiledByRuleId(null);
            m.setFiledAt(null);
            return Outcome.UNFILED;
        }

        MailRule rule = match.get();
        boolean moved = m.getFolder() == null || !rule.getFolder().getId().equals(m.getFolder().getId());
        m.setFolder(rule.getFolder());
        m.setFiledByRuleId(rule.getId());
        m.setFiledAt(LocalDateTime.now());

        boolean markedRead = false;
        if (rule.isMarkRead() && !m.isRead()) {
            m.setRead(true);
            markedRead = true;
        }
        if (moved) {
            return markedRead ? Outcome.FILED_AND_READ : Outcome.FILED;
        }
        return markedRead ? Outcome.READ_ONLY : Outcome.UNTOUCHED;
    }

    /** What {@link #fileByRules} did to a message. */
    public enum Outcome {
        UNTOUCHED, FILED, FILED_AND_READ, READ_ONLY, UNFILED;

        public boolean moved() {
            return this == FILED || this == FILED_AND_READ || this == UNFILED;
        }

        public boolean markedRead() {
            return this == FILED_AND_READ || this == READ_ONLY;
        }
    }

    /**
     * Whether the rules are allowed to move this message: it is in the Inbox, or a rule put
     * it where it is. See the class note — hand-filed mail is off limits.
     */
    private static boolean ruleManaged(MailMessage m) {
        return m.getFolder() == null || m.getFiledByRuleId() != null;
    }

    /**
     * Re-runs every rule over the mail already stored — what the "Apply rules now" button
     * does after a rule is added or edited.
     *
     * <p>Paged rather than loaded whole: a mailbox that has been syncing for a year is tens
     * of thousands of rows with their bodies attached, and reading all of it into the heap to
     * test a substring would be the most expensive thing the app does. The page window is
     * stable under the operation because a message that gets filed still satisfies the same
     * query, so nothing is skipped by rows shifting between pages.
     */
    @Transactional
    public MailRuleRunResponse applyToExisting() {
        List<MailRule> ordered = rules.findEnabledForEvaluation();
        Specification<MailMessage> ruleManaged = (root, query, cb) -> cb.or(
                cb.isNull(root.get("folder")),
                cb.isNotNull(root.get("filedByRuleId")));

        int evaluated = 0;
        int filed = 0;
        int markedRead = 0;
        int page = 0;
        while (true) {
            var slice = messages.findAll(ruleManaged,
                    PageRequest.of(page, RUN_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")));
            if (slice.isEmpty()) break;

            for (MailMessage m : slice) {
                evaluated++;
                Outcome outcome = fileByRules(m, ordered);
                if (outcome.moved()) filed++;
                if (outcome.markedRead()) markedRead++;
            }
            messages.saveAll(slice.getContent());
            if (slice.isLast()) break;
            page++;
        }
        log.info("Applied {} mail rules over {} messages: {} refiled, {} marked read",
                ordered.size(), evaluated, filed, markedRead);
        return new MailRuleRunResponse(evaluated, filed, markedRead);
    }

    // ---------------------------------------------------------------- matching

    private boolean matches(MailRule rule, MailMessage m) {
        List<MailRuleCondition> conditions = rule.getConditions();
        if (conditions.isEmpty()) {
            // Defensive: validation refuses to save one, but a row edited straight in the
            // database must not turn into "matches everything".
            return false;
        }
        return rule.getMatchType() == MailRule.MatchType.ALL
                ? conditions.stream().allMatch(c -> matches(c, m))
                : conditions.stream().anyMatch(c -> matches(c, m));
    }

    private boolean matches(MailRuleCondition c, MailMessage m) {
        String haystack = haystack(c.getField(), m);
        String needle = c.getValue().toLowerCase(Locale.ROOT);
        if (haystack == null) {
            // Nothing to test against. Only NOT_CONTAINS can be true of an absent field,
            // and it should be: "no subject" does not contain "invoice".
            return c.getOperator() == MailRuleCondition.Operator.NOT_CONTAINS;
        }
        String hay = haystack.toLowerCase(Locale.ROOT);
        return switch (c.getOperator()) {
            case CONTAINS -> hay.contains(needle);
            case NOT_CONTAINS -> !hay.contains(needle);
            case EQUALS -> hay.trim().equals(needle.trim());
            case STARTS_WITH -> hay.trim().startsWith(needle);
            case ENDS_WITH -> hay.trim().endsWith(needle);
        };
    }

    private static String haystack(MailRuleCondition.Field field, MailMessage m) {
        return switch (field) {
            // Address and display name together: a rule for "Maersk" should find the
            // message whether the name or the domain is what carries it.
            case FROM -> join(m.getFromAddress(), m.getFromName());
            case FROM_DOMAIN -> domainOf(m.getFromAddress());
            case TO -> join(m.getToAddresses(), m.getCcAddresses());
            case SUBJECT -> m.getSubject();
            case BODY -> m.getBodyText();
            case ANY -> join(m.getFromAddress(), m.getFromName(), m.getToAddresses(),
                    m.getCcAddresses(), m.getSubject(), m.getBodyText());
        };
    }

    /** The part after the @, so a domain rule cannot be fooled by a display name quoting it. */
    private static String domainOf(String address) {
        if (address == null) return null;
        int at = address.lastIndexOf('@');
        return at < 0 || at == address.length() - 1 ? null : address.substring(at + 1);
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(p);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ---------------------------------------------------------------- parsing

    private static MailRule.MatchType parseMatchType(String raw) {
        if (raw == null || raw.isBlank()) return MailRule.MatchType.ALL;
        try {
            return MailRule.MatchType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Match type must be ALL or ANY, got \"" + raw + "\".");
        }
    }

    private static MailRuleCondition.Field parseField(String raw) {
        try {
            return MailRuleCondition.Field.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "\"" + raw + "\" is not a field a rule can test. Use FROM, FROM_DOMAIN, "
                            + "TO, SUBJECT, BODY or ANY.");
        }
    }

    private static MailRuleCondition.Operator parseOperator(String raw) {
        if (raw == null || raw.isBlank()) return MailRuleCondition.Operator.CONTAINS;
        try {
            return MailRuleCondition.Operator.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "\"" + raw + "\" is not a comparison. Use CONTAINS, NOT_CONTAINS, EQUALS, "
                            + "STARTS_WITH or ENDS_WITH.");
        }
    }
}
