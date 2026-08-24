package com.chartering.audit;

import com.chartering.model.AppSetting;
import com.chartering.model.CirculationList;
import com.chartering.model.Company;
import com.chartering.model.Contact;
import com.chartering.model.EmailFooter;
import com.chartering.model.EmailTemplate;
import com.chartering.model.MailFolder;
import com.chartering.model.MailRule;
import com.chartering.model.MailRuleCondition;
import com.chartering.model.Person;
import com.chartering.model.Vessel;
import com.chartering.model.VesselCompanyLink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Which entities are logged, what the log calls them, and how a row of each describes
 * itself.
 *
 * <p><b>A whitelist, not a blacklist, and that is the important decision here.</b> "Log
 * everything" sounds like the safer default and is not: {@code mail_messages} grows by every
 * message the IMAP sync has ever seen, and auditing it would write more history rows per
 * sync than the sync writes messages, to record that a machine copied a mailbox to itself.
 * The log is for changes somebody <em>made</em>, so the set below is the entities somebody
 * can edit from a screen.
 *
 * <p>Left out on purpose, each for its own reason:
 *
 * <ul>
 *   <li>{@code MailMessage}, {@code MailServerFolder}, {@code MailSyncState} — ingested from
 *       the mail server, not authored. Their history is the mailbox.
 *   <li>{@code CirculationRun}, {@code CirculationRunRecipient} — already a history table.
 *       Logging the log says nothing new.
 *   <li>{@code CirculationListEntry} — a circulation list is a working document, rebuilt
 *       from the contacts on every send; a bulk add would write a row per recipient to
 *       record a selection that is reproducible from the contact data, which <em>is</em>
 *       logged. The list itself is logged, so renaming or deleting one is still visible.
 *   <li>{@code Port}, {@code Region}, {@code TonnageCategory} — reference tables with no
 *       screen that writes to them.
 * </ul>
 *
 * <p>Adding one is a line in {@link #AUDITED}. Nothing else in the audit code names an
 * entity, so that line is the whole change.
 */
public final class AuditedEntities {

    /**
     * Fields never worth a log row.
     *
     * <p>{@code createdAt} and {@code updatedAt} are maintained by Hibernate on every save,
     * so logging them would put a "updatedAt changed" row beside every real change and one
     * on its own beside every save that changed nothing else. What they would have recorded,
     * {@code changed_at} already records more precisely.
     */
    static final Set<String> IGNORED_FIELDS = Set.of("createdAt", "updatedAt");

    private record Audited(String type, Function<Object, String> label) {
    }

    private static final Map<Class<?>, Audited> AUDITED = new LinkedHashMap<>();

    static {
        audit(Company.class, "company", e -> ((Company) e).getName());
        audit(Person.class, "person", e -> ((Person) e).getFullName());
        // An address describes itself by its value; nothing else about a contact row tells
        // two of them apart at a glance.
        audit(Contact.class, "contact", e -> ((Contact) e).getContactValue());
        audit(Vessel.class, "vessel", e -> ((Vessel) e).getName());
        audit(VesselCompanyLink.class, "vessel-link", e -> null);
        audit(CirculationList.class, "circulation-list", e -> ((CirculationList) e).getName());
        audit(EmailTemplate.class, "email-template", e -> ((EmailTemplate) e).getName());
        audit(EmailFooter.class, "email-footer", e -> ((EmailFooter) e).getName());
        audit(MailFolder.class, "mail-folder", e -> ((MailFolder) e).getName());
        audit(MailRule.class, "mail-rule", e -> ((MailRule) e).getName());
        audit(MailRuleCondition.class, "mail-rule-condition", e -> null);
        // Settings are one row per key and the key is the whole identity of the row.
        audit(AppSetting.class, "setting", e -> ((AppSetting) e).getKey());
    }

    private static void audit(Class<?> type, String name, Function<Object, String> label) {
        AUDITED.put(type, new Audited(name, label));
    }

    private AuditedEntities() {
    }

    /** The log's name for this entity, or null if it is not logged. */
    public static String typeOf(Object entity) {
        Audited audited = lookup(entity);
        return audited == null ? null : audited.type();
    }

    /**
     * What to call this row in the log. Trimmed to the column width here rather than at the
     * insert, because a label is a convenience and truncating one is never worth failing a
     * save that has otherwise already succeeded.
     */
    public static String labelOf(Object entity) {
        Audited audited = lookup(entity);
        if (audited == null) return null;
        String label;
        try {
            label = audited.label().apply(entity);
        } catch (RuntimeException e) {
            // A lazy proxy that cannot initialise, most likely. A missing label costs a
            // line of readability; an exception here would cost the whole transaction.
            return null;
        }
        if (label == null || label.isBlank()) return null;
        return label.length() > 255 ? label.substring(0, 255) : label;
    }

    private static Audited lookup(Object entity) {
        if (entity == null) return null;
        // Walks the hierarchy so a Hibernate proxy subclass resolves to the entity it
        // stands for rather than falling through as unaudited.
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            Audited audited = AUDITED.get(c);
            if (audited != null) return audited;
        }
        return null;
    }

    /** The entity class behind one of the log's type names, for the revert to work from. */
    public static Class<?> classOf(String type) {
        return AUDITED.entrySet().stream()
                .filter(e -> e.getValue().type().equals(type))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    /** Every type name the log can carry, for the filter dropdown. */
    public static java.util.List<String> types() {
        return AUDITED.values().stream().map(Audited::type).sorted().toList();
    }
}
