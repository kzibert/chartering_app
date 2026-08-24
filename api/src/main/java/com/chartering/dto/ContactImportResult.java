package com.chartering.dto;

import java.util.List;

/**
 * What the import actually did. Counted from the writes rather than from the request, so a
 * row that matched an existing person and added nothing new is visible as such.
 *
 * @param contactsSkipped addresses that were already on file against the same company. The
 *                        preview flags these and unticks them, but the check runs again
 *                        here — the preview was taken against the database as it stood when
 *                        the file was uploaded, and nothing stops the same file being
 *                        imported twice.
 */
public record ContactImportResult(
        int companiesCreated,
        int companiesMatched,
        int peopleCreated,
        int peopleMatched,
        int contactsCreated,
        int contactsSkipped,
        List<String> messages) {
}
