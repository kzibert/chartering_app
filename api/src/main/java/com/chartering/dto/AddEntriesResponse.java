package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a bulk add did: rows written, rows already on the list, and rows that were not
 * usable addresses at all.
 *
 * <p>The last group is the contact data's own dirt — an address typed with a space in it,
 * a trailing pipe left over from an import. A bulk add collects hundreds of addresses the
 * user never typed, so one bad row cannot be allowed to reject the whole batch; it is
 * reported back instead, named, so the contact record can be corrected.
 *
 * @param added        rows written to the list
 * @param skipped      addresses already on the list (repeats within the batch included)
 * @param invalid      rows dropped as unusable addresses
 * @param invalidEmails the unusable addresses, deduplicated, for the message on screen
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddEntriesResponse(int added, int skipped, int invalid, List<String> invalidEmails) {
}
