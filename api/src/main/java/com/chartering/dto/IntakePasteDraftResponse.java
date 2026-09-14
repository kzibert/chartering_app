package com.chartering.dto;

import com.chartering.service.parser.CompanyMatcher;
import com.chartering.service.parser.IntakeResolver;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a pasted text was read into — every part a draft, nothing written.
 *
 * <p><b>The drafts are the request bodies the ordinary forms already send.</b> A cargo draft is
 * a {@link CargoRequest}, a position a {@link VesselPositionRequest}, and so on, so the review
 * screen opens the same forms the Cargoes and Open Fleet tabs use, prefilled, and saving goes
 * through the same endpoints with the same rules. A second, paste-shaped way to create a cargo
 * would be a second set of rules about what a cargo is.
 *
 * <p>Nothing is stored between reading and saving, the importer's arrangement: the whole
 * reading travels to the browser, a person accepts it part by part, and an abandoned paste
 * costs nothing.
 *
 * @param modelRead  whether the model read the text; false when it is switched off or not
 *                   answering, in which case only the company block — read without it — is here
 * @param modelError why not, in words, when it did not
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IntakePasteDraftResponse(
        String type,
        String summary,
        boolean modelRead,
        String modelError,
        List<CargoDraft> cargoes,
        List<VesselDraft> vessels,
        CompanyDraft company) {

    /**
     * @param chartererAsWritten the charterer's name when it named no company on file
     * @param duplicate          a live cargo this looks like, when there is one — saving would
     *                           then be a second record of the same enquiry
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CargoDraft(CargoRequest cargo, String chartererAsWritten, DuplicateHint duplicate) {
    }

    public record DuplicateHint(Long cargoId, String commodity, List<String> reasons) {
    }

    /**
     * @param vessel      her particulars as the text gave them, capacities in cubic metres
     * @param match       the hull on file she is, by IMO or by name (current or former)
     * @param suggestions hulls that only resemble her, when nothing matched exactly
     * @param position    her open position, when the text gave one; its vesselId is the match's
     *                    and absent when she has none — a position cannot be saved against a
     *                    ship that does not exist yet
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VesselDraft(VesselRequest vessel,
                              VesselMatchHint match,
                              List<IntakeResolver.Suggestion> suggestions,
                              VesselPositionRequest position) {
    }

    /** @param how IMO, NAME or EX_NAME — the resolver's own words for it */
    public record VesselMatchHint(Long vesselId, String name, String imoNumber, String how) {
    }

    /**
     * @param company the firm as a {@link CompanyRequest}; the street address goes in its notes,
     *                there being no column for one
     * @param matches companies on file it might be, strongest first, each with its reasons
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CompanyDraft(CompanyRequest company,
                               String address,
                               List<PersonDraft> people,
                               List<ContactDraft> contacts,
                               List<CompanyMatcher.Match> matches) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PersonDraft(String fullName, String title, String jobTitle) {
    }

    /** @param personName which of {@code people} this line belongs to; absent for a desk address */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContactDraft(String kind, String value, String label, String personName) {
    }
}
