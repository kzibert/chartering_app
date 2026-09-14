package com.chartering.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What accepting a pasted company did, for the review screen's one-line answer.
 *
 * @param skipped the addresses and numbers left out because the company already had them,
 *                named — "added 3, 2 already on file" is only useful if it says which two
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakePasteCompanyResponse(Long companyId,
                                         String companyName,
                                         boolean created,
                                         int companyFieldsUpdated,
                                         int peopleAdded,
                                         int peopleUpdated,
                                         int contactsAdded,
                                         int contactsUpdated,
                                         List<String> skipped) {
}
