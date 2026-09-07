package com.chartering.service;

import com.chartering.model.AnalysisLabel;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The shape a labelled email is written down in — one skeleton per {@link AnalysisLabel}.
 *
 * <p><b>Why the server holds these rather than the reviewer.</b> A finetuning set teaches a
 * model to produce a particular shape, and it can only do that if the shape is the same in
 * every example. Left to a form with an empty text box, the first fifty samples say
 * {@code loadPort} and the next fifty say {@code load_port}, and the resulting model
 * produces both — the mistake is invisible while it is being made and expensive afterwards,
 * because fixing it means re-reading every sample.
 *
 * <p><b>Every field below names a column something can be written to.</b> That is the rule
 * the shape is built on, and it is what the first version of this file got wrong. A model
 * that reads an email perfectly and returns fields nothing stores has not moved a cargo any
 * closer to a ship: {@link MatchScorer} weights size, timing, cubic, draft, gear, fittings
 * and age, and an extraction carrying none of draft, DWCC, stowage factor or the fitting
 * flags scores UNKNOWN on almost every test it is put to. So each field here is paired in
 * the comments with the column it feeds — {@code Cargo}, {@code Vessel} or
 * {@code VesselPosition} — and a field that could name no column was left out.
 *
 * <p><b>They are suggestions, not a schema.</b> Nothing validates an annotation against
 * these; the service only checks that what is stored parses as JSON. That is deliberate:
 * the extraction shape is still moving, and a validator would turn every discovery into a
 * migration. What has been done instead is to read a mailbox's worth of real circulars and
 * let the fields follow what brokers actually write — which is where the units, the
 * tri-state flags and the raw-text twins below all come from.
 *
 * <p><b>Three states, because the scorer has three verdicts.</b> {@link MatchScorer} answers
 * PASS, FAIL or UNKNOWN, and UNKNOWN is the one that carries the fleet: half these hulls
 * have no gear recorded. So a flag the email does not mention must arrive as {@code null}
 * and not as {@code false} — {@code false} is a claim ("she is not geared") that becomes a
 * FAIL and rules the pair out, and a corpus that said {@code false} whenever an email was
 * silent would teach the model to rule out most of the tonnage on the desk. The same goes
 * for numbers: {@code null} is "the email does not say", never zero.
 *
 * <p><b>How "not stated" is written depends on the type, and that is the whole convention.</b>
 * A string field says it with {@code ""}, a number or a flag says it with {@code null},
 * because those are the only values their types allow. The earlier version asked the
 * reviewer to keep {@code ""} ("does not say") apart from {@code null} ("not applicable")
 * inside one untyped field, which is a judgement call on every line of a thirty-field form
 * and was never going to be made the same way twice. Nothing is collapsed by moving it — the
 * distinction now rides on the field's type, where it cannot drift.
 *
 * <p><b>The words and the number are both kept, and they are different fields.</b> The app
 * already works this way — {@code loadPortText} beside {@code loadPort}, {@code laycanText}
 * beside {@code laycanFrom}, {@code quantityTolerance} beside {@code quantityMin} — because
 * what the broker wrote is evidence and what the matcher compares is arithmetic. So the
 * model is asked for both: copy the phrase, and give the number when the phrase states one
 * outright. When it does not state one, the number is {@code null} and stays {@code null}.
 * "MOLOO" is the standing example: it is a percentage the charter party settles and this
 * email does not name, so it produces no range at all rather than a guessed five percent.
 * See {@link QuantityTolerance}, which draws the same line for the same reason.
 */
public final class AnalysisAnnotationTemplates {

    private AnalysisAnnotationTemplates() {
    }

    /**
     * The instruction every exported example is trained against.
     *
     * <p>One prompt for the whole set, not one per label: at inference time nobody knows yet
     * which kind of email has arrived — that is the thing being asked. So the prompt has to
     * be the one a real caller can send, and the label is part of the answer rather than
     * part of the question.
     *
     * <p>The conventions in it are not style notes. Each one is a trap this mailbox actually
     * sprang, and a prompt that leaves one out gets a corpus that answers it two ways:
     *
     * <ul>
     *   <li><b>"3.200 CC/144.000 Cbf"</b> — European thousands separators. Read as decimal
     *       points that is a 3.2-tonne ship, and the size check quietly fails every cargo.</li>
     *   <li><b>"95 blt"</b> — a two-digit build year, beside "Blt 1997" in the next mail.</li>
     *   <li><b>cbft or cbm</b> — both are written, a factor of thirty-five apart, and the
     *       field name must never assume one. Hence a unit field rather than {@code grainM3}.</li>
     *   <li><b>"4 crane, SWL 12.5 mts, positioned btwn holds - Considered as GearLess"</b> —
     *       the broker's own conclusion overrides the crane count, and the sentence is worth
     *       keeping whole.</li>
     *   <li><b>"App B fitted"</b> — Appendix B is grain fitting under another name.</li>
     *   <li><b>"DWT: 5,250 Mt on 6 m draft"</b> — the draft the matcher needs is inside the
     *       deadweight sentence, not on a line of its own.</li>
     * </ul>
     *
     * <p>The one inference allowed is the year on a date, and only because the email's own
     * date is given to the model in the user turn. "OPEN 07/10 SEPTEMBER" is a laycan in the
     * near future of the mail that carried it; a date with no year cannot be compared to
     * anything, so refusing to supply one would leave every timing check UNKNOWN.
     */
    public static final String SYSTEM_PROMPT = """
            You read shipping emails for a dry-cargo chartering desk and return JSON only.

            Classify the email as one of: cargo_offer, vessel_opening, mixed, other. Then \
            extract every cargo on offer and every vessel position it contains, one object \
            per cargo and per vessel.

            Rules:
            - A field the email does not state is "" for text and null for numbers and \
            true/false flags. Never write false or 0 to mean "not stated" — false is a claim \
            that the ship or the cargo lacks the thing.
            - Copy the broker's own words into the text fields. Fill a number field only when \
            the words state that number outright; if reading it would need a guess, leave it \
            null and let the words carry it.
            - Digits are written with European separators: "3.200" and "13.500" are 3200 and \
            13500, not decimals. "95 blt" is 1995.
            - Never convert units. Record grain and bale as written and say which in \
            capacityUnit ("CBM" or "CBFT").
            - Dates are ISO (YYYY-MM-DD). Take the year from the email's own date unless the \
            email gives one. Keep the phrase as written in the matching text field too: \
            "SPOT", "spot/prompt" and "end Sept" are positions, not dates, and leave \
            from/to empty.
            - "App B" or "Appendix B" is grain fitted. If the broker calls a geared ship \
            gearless, follow the broker and keep the whole sentence in gearDescription.

            Return the JSON object and nothing else.""";

    /**
     * A charterer's requirement as it arrived.
     *
     * <p>Field for field this is {@code Cargo}, and almost every one of them is nullable
     * there for the same reason it is optional here: a real first email says "25,000 MT
     * Wheat +/- 10%, Chornomorsk to Spain Med, geared bulker abt 28-35,000 DWT, laycan
     * please advise" and stops. The requirement fields — {@code minDwt} through
     * {@code requiresImoFitted} — are the ones the earlier shape had no room for at all, and
     * they are precisely the ones that turn a cargo into something a hull can be measured
     * against. {@code stowageFactor} is the same story: it is the cubic check's only input,
     * it is written on the offers this desk sends ("Stowage factor: about 53"), and nothing
     * was collecting it.
     */
    private static final String CARGO_OFFER = """
            {
              "type": "cargo_offer",
              "cargoes": [
                {
                  "commodity": "",
                  "quantity": null,
                  "quantityUnit": "",
                  "quantityTolerance": "",
                  "quantityMin": null,
                  "quantityMax": null,
                  "stowageFactor": null,
                  "loadPort": "",
                  "loadArea": "",
                  "dischargePort": "",
                  "dischargeArea": "",
                  "laycanFrom": "",
                  "laycanTo": "",
                  "laycanText": "",
                  "loadRate": "",
                  "dischargeRate": "",
                  "minDwt": null,
                  "maxDwt": null,
                  "maxDraft": null,
                  "maxAgeYears": null,
                  "requiresGeared": null,
                  "requiresGrainFitted": null,
                  "requiresImoFitted": null,
                  "freightIdea": "",
                  "commission": "",
                  "terms": "",
                  "charterer": "",
                  "notes": ""
                }
              ],
              "vessels": [],
              "broker": { "company": "", "person": "", "email": "" }
            }""";

    /**
     * One vessel's open position — a {@code Vessel} and the {@code VesselPosition} reporting
     * her, in one object because one paragraph of a circular carries both.
     *
     * <p>{@code dwcc} and {@code draft} are the two additions that matter most. DWCC is the
     * number the size check wants and the one brokers actually quote ("3.200 CC", "13.500
     * DWCC"); draft decides whether she can enter the berth, and the earlier shape had
     * neither. {@code cargoPreferences} is here because nearly every circular ends with one
     * — "Pref: Within Black Sea / Med Sea", "looking for part cargo to Libyan ports" — and
     * it is a column on {@code VesselPosition} that was going unfilled.
     *
     * <p>{@code openArea} takes the spelling as written, not a canonical name.
     * {@code trade_area_aliases} is what resolves "W.MED", "SPAIN MED" and "TBS" onto one
     * water, and a model asked to canonicalise would be guessing at a table it cannot see —
     * badly, and without leaving a record of what it was told.
     */
    private static final String VESSEL_OPENING = """
            {
              "type": "vessel_opening",
              "cargoes": [],
              "vessels": [
                {
                  "name": "",
                  "imo": "",
                  "vesselType": "",
                  "dwt": null,
                  "dwcc": null,
                  "draft": null,
                  "built": null,
                  "flag": "",
                  "grainCapacity": null,
                  "baleCapacity": null,
                  "capacityUnit": "",
                  "geared": null,
                  "gearDescription": "",
                  "holds": null,
                  "hatches": null,
                  "grainFitted": null,
                  "timberFitted": null,
                  "imoFitted": null,
                  "iceClass": "",
                  "openPort": "",
                  "openArea": "",
                  "openFrom": "",
                  "openTo": "",
                  "openText": "",
                  "lastCargo": "",
                  "cargoPreferences": "",
                  "notes": ""
                }
              ],
              "broker": { "company": "", "person": "", "email": "" }
            }""";

    /**
     * Both in one message — the daily circular that lists a page of cargoes and a page of
     * open tonnage. Same two arrays as the single-kind templates, which is the point: an
     * exported set has one output shape, and "mixed" is a value of {@code type} rather than
     * a different document.
     */
    private static final String BOTH = """
            {
              "type": "mixed",
              "cargoes": [],
              "vessels": [],
              "broker": { "company": "", "person": "", "email": "" }
            }""";

    /**
     * Neither — a fixture report, a negotiation, an invoice, an out-of-office.
     *
     * <p>Worth annotating rather than skipping: a model that has never been shown an email
     * with nothing in it will find a cargo in an out-of-office reply. The empty arrays are
     * the lesson.
     */
    private static final String OTHER = """
            {
              "type": "other",
              "cargoes": [],
              "vessels": [],
              "summary": ""
            }""";

    private static final Map<String, String> TEMPLATES = buildTemplates();

    /** Keyed by enum name, which is what the DTO and the UI both speak. */
    public static Map<String, String> all() {
        return TEMPLATES;
    }

    public static String forLabel(AnalysisLabel label) {
        return TEMPLATES.get(label.name());
    }

    private static Map<String, String> buildTemplates() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(AnalysisLabel.CARGO_OFFER.name(), CARGO_OFFER);
        m.put(AnalysisLabel.VESSEL_OPENING.name(), VESSEL_OPENING);
        m.put(AnalysisLabel.BOTH.name(), BOTH);
        m.put(AnalysisLabel.OTHER.name(), OTHER);
        // No entry for UNLABELLED on purpose: there is nothing to write down until somebody
        // has said what the email is, and prefilling one shape would be a guess the reviewer
        // then has to notice and undo.
        // unmodifiableMap, not Map.copyOf: the order the reviewer sees these in is the order
        // they were written here, and Map.copyOf does not promise one.
        return Collections.unmodifiableMap(m);
    }
}
