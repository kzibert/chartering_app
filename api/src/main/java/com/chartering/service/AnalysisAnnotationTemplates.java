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
 * <p><b>They are suggestions, not a schema.</b> Nothing validates an annotation against
 * these; the service only checks that what is stored parses as JSON. That is deliberate at
 * this stage: the fields below are a first guess at what a broker's circular actually
 * contains, and the way to improve them is to label a hundred emails and find out what would
 * not fit. A validator would turn every one of those discoveries into a migration.
 *
 * <p>Empty string and null mean different things and both are kept: {@code ""} is "the email
 * does not say", {@code null} is "not applicable to this line". A model trained on a corpus
 * that collapses the two learns to invent the missing half.
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
     */
    public static final String SYSTEM_PROMPT = """
            You read shipping emails for a dry-cargo chartering desk and return JSON only.

            Classify the email as one of: cargo_offer, vessel_opening, mixed, other. Then \
            extract every cargo on offer and every vessel position it contains, one object \
            per cargo and per vessel.

            Use "" for a detail the email does not give. Do not infer, complete or convert \
            anything: copy dates, quantities and port names as they are written. Return the \
            JSON object and nothing else.""";

    private static final String CARGO_OFFER = """
            {
              "type": "cargo_offer",
              "cargoes": [
                {
                  "commodity": "",
                  "quantity": "",
                  "quantityTolerance": "",
                  "loadPort": "",
                  "loadRange": "",
                  "dischargePort": "",
                  "dischargeRange": "",
                  "laycanFrom": "",
                  "laycanTo": "",
                  "terms": "",
                  "freightIdea": "",
                  "commission": "",
                  "notes": ""
                }
              ],
              "vessels": [],
              "broker": { "company": "", "person": "" }
            }""";

    private static final String VESSEL_OPENING = """
            {
              "type": "vessel_opening",
              "cargoes": [],
              "vessels": [
                {
                  "name": "",
                  "imo": "",
                  "vesselType": "",
                  "dwt": "",
                  "built": "",
                  "flag": "",
                  "grainM3": "",
                  "baleM3": "",
                  "gear": "",
                  "openPort": "",
                  "openRange": "",
                  "openFrom": "",
                  "openTo": "",
                  "lastCargo": "",
                  "notes": ""
                }
              ],
              "broker": { "company": "", "person": "" }
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
              "broker": { "company": "", "person": "" }
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
