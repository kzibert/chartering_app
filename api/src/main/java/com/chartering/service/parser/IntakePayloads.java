package com.chartering.service.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What each kind of review item carries, and what the drawer renders.
 *
 * <p>Typed records rather than a loose {@code JsonNode}, even though the column is text and
 * nothing queries inside it. The shape is free to move between releases — that is the whole
 * argument for storing it as text — but within one release it has to be exactly one thing,
 * or the screen and the accept endpoint end up reading the same payload two ways. Jackson
 * reads them back leniently ({@code ignoreUnknown}), so an item raised by yesterday's build
 * still opens after a deploy that added a field.
 *
 * <p>Each payload keeps <b>the extraction it came from</b>, not just the diff. Accepting is
 * re-derived from that, so what lands in the column has been through the same reading as
 * what was compared — a display string parsed back into a number is how a draft of 7.9 m
 * becomes 79.
 */
public final class IntakePayloads {

    private IntakePayloads() {
    }

    /**
     * A hull neither the IMO nor the name found.
     *
     * @param vessel      the reading, whole: her particulars and her position in one object,
     *                    because accepting has to create both
     * @param searchedBy  what was looked for, so the screen can say "no match on IMO 9123456
     *                    or the name ATLANTIC BREEZE" rather than only "not found"
     * @param suggestions the third tier — hulls whose particulars resemble this one, ranked,
     *                    each carrying the evidence for itself. Snapshotted when the item is
     *                    raised rather than computed when the drawer opens: the shortlist a
     *                    person is answering should be the one the parse actually produced,
     *                    and recomputing it would quietly change the question between the
     *                    list saying "3 suggestions" and the drawer showing them
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewVessel(Extraction.ExtractedVessel vessel,
                            String searchedBy,
                            List<IntakeResolver.Suggestion> suggestions) {
    }

    /**
     * She is on file and the email disagrees about her.
     *
     * @param matchedBy how she was identified — {@code IMO}, {@code NAME} or {@code EX_NAME}.
     *                  A code rather than a sentence, because the screen ranks it: an IMO
     *                  match is near-certain and a former-name match is the one worth a second
     *                  look, and a browser cannot rank English. Items raised before this was a
     *                  code carry the sentence, which the UI prints as it stands
     * @param filled    fields that were empty and have already been written — shown so the
     *                  screen can account for everything the email said, rather than listing
     *                  three conflicts and silently having changed five other columns
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VesselFields(Long vesselId,
                               String vesselName,
                               String matchedBy,
                               Extraction.ExtractedVessel vessel,
                               List<FieldDiff> diffs,
                               List<String> filled) {
    }

    /**
     * A cargo that looks like one already in hand.
     *
     * @param reasons   what actually matched, in the words the screen prints. A merge is a
     *                  judgement and a judgement needs evidence, not a score
     * @param wouldFill fields the existing cargo is missing that this reading would supply
     * @param differing fields the two disagree about, which a merge leaves alone — see
     *                  {@link CargoFieldDiff}
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CargoMerge(Extraction.ExtractedCargo cargo,
                             Long candidateId,
                             String candidateLabel,
                             List<String> reasons,
                             List<String> wouldFill,
                             List<FieldDiff> differing) {
    }

    /**
     * The firm that signed a circular, against the firm on file.
     *
     * <p><b>{@code draft} is the paste screen's own {@code CompanyDraft}, deliberately.</b> The
     * modal that reviews a pasted signature is already written, already handles the three shapes
     * this item comes in — create, update, "which of these firms is it" — and already sends an
     * {@code IntakePasteCompanyRequest} back. Carrying the same record means the drawer renders
     * it with that component rather than a second one, and accepting goes through
     * {@code IntakePasteService.acceptCompany} rather than a second idea of what a signature may
     * write.
     *
     * @param companyId  the firm on file this is about, when one piece of identity evidence said
     *                   so; null is the question "which firm, if any" — the candidates are in
     *                   {@code draft.matches()}
     * @param matchedBy  {@code email}, {@code name} or {@code phone} — how it was identified, so
     *                   the drawer can say why it is sure rather than only that it is
     * @param changes    what there is to decide, in the words the queue row prints
     * @param styleHash  a fingerprint of what the block said. What a discard suppresses: without
     *                   it "do not file these details" would last until the same broker's next
     *                   list, because the comparison would find the same rows again. Only a
     *                   signature that has moved comes back
     * @param minor      nothing in it touches the firm's name or an email address — see
     *                   {@code CompanyStyleIntake#isMinor}. Null on items raised before V28
     * @param seenStyles every fingerprint aggregated into this item, since the draft is now the
     *                   union of every signature behind it rather than the newest one. A discard
     *                   has to suppress all of them, or the first broker's next list would bring
     *                   back half of what was just turned down
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CompanyDetails(com.chartering.dto.IntakePasteDraftResponse.CompanyDraft draft,
                                 Long companyId,
                                 String companyName,
                                 String matchedBy,
                                 List<String> changes,
                                 String styleHash,
                                 Boolean minor,
                                 List<String> seenStyles) {

        public CompanyDetails(com.chartering.dto.IntakePasteDraftResponse.CompanyDraft draft,
                              Long companyId, String companyName, String matchedBy,
                              List<String> changes, String styleHash) {
            this(draft, companyId, companyName, matchedBy, changes, styleHash, null, null);
        }

        public boolean isMinor() {
            return Boolean.TRUE.equals(minor);
        }
    }
}
