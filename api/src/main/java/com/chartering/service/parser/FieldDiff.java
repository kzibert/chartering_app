package com.chartering.service.parser;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One field the database and an email disagree about, as the review table prints it.
 *
 * <p>Both values are strings by the time they get here, and that is deliberate. The screen's
 * job is to show a person two readings side by side so they can pick one; keeping the values
 * typed would mean a union in the stored payload and a renderer per type, to display text
 * either way. What accepting a row <em>writes</em> is never taken from these strings — it is
 * re-derived from the stored extraction, so the value that lands in the column has been
 * through exactly the same reading as the one that was compared.
 *
 * @param field    the entity's own property name, which is what an accept request names
 * @param label    what the record's edit form calls it, so the two screens agree
 * @param current  what is on file, printed with its unit
 * @param incoming what the email said, printed the same way
 * @param minor    true where {@code VesselReviewPolicy} judged the row not worth the queue —
 *                 a small difference, a value already decided, a record the market backs.
 *                 Null on rows nobody weighed (cargo rows, items raised before V28)
 * @param note     why, in the words the drawer prints beside the row
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FieldDiff(String field, String label, String current, String incoming,
                        Boolean minor, String note) {

    public FieldDiff(String field, String label, String current, String incoming) {
        this(field, label, current, incoming, null, null);
    }

    /** The same row, weighed. */
    public FieldDiff weighed(boolean isMinor, String why) {
        return new FieldDiff(field, label, current, incoming, isMinor, why);
    }

    public boolean isMinor() {
        return Boolean.TRUE.equals(minor);
    }
}
