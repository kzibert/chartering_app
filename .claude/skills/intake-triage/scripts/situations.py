"""The situations a review decision is filed under, and where in the code each one points.

A situation is a *shape* of evidence, never a firm or a ship, so the counts across triages say
which part of the pipeline to improve: the model's reading (PARSE_*), the comparison and
vocabulary (NORMALISE_*, PLAUSIBILITY_*), the queue's own bookkeeping (QUEUE_*), the web lookup
(LOOKUP_*), the company reader and matcher (COMPANY_*), or a fact about trust (TRUST_*).
DECIDED_* are the answers no rule could have given - they are what a person is for.

Add a code when a situation recurs that none of these names; keep the area pointing at code.
"""

SITUATIONS = {
    # the model's reading of the text
    "PARSE_NOT_A_VESSEL": ("Read space / cargo on offer, a liner slot or a code as a ship", "parser prompt / training data; IntakeResolver"),
    "PARSE_NAME_GARBLED": ("Ship name misread, truncated or a fragment of another word", "parser prompt / training data"),
    "PARSE_FIELD_MISREAD": ("A value landed in the wrong field (build place as flag, note as gear)", "parser prompt / extraction schema"),
    "PARSE_UNIT_WRONG": ("Capacity read in the wrong unit", "parser; CapacityUnits"),
    "PARSE_SOURCE_TYPO": ("The text itself carries an impossible figure (typo in the email)", "VesselFieldDiff plausibility bands"),
    "PARSE_PERSON_IS_FIRM": ("The firm's own name read as a person", "CompanyStyleReader"),
    "PARSE_COMPANY_FIELDS": ("Signature block read into the wrong company fields", "CompanyStyleReader / parser company section"),
    # comparison and vocabulary
    "NORMALISE_SPELLING": ("Same value spelled differently (flag demonym, '&'/'and', case, typo, gear wording)", "VesselFieldDiff; a flag / gear vocabulary"),
    "NORMALISE_NAME_KEY": ("Names equal after Unicode case folding / accent stripping / a dropped prefix", "name keys in IntakeResolver and repositories"),
    "PLAUSIBILITY_INCOMING": ("Incoming figure impossible for her size (draft, DWCC > DWT, capacity)", "VesselFieldDiff plausibility bands"),
    "PLAUSIBILITY_RECORD": ("The record holds the impossible figure; the email fixes it", "VesselFieldDiff plausibility bands; data fix"),
    "ROUNDED_FIGURE": ("Email gives a round 'about' figure against an exact one", "VesselFieldDiff.smallDifference; creation from lookup"),
    # the queue's own bookkeeping
    "QUEUE_DUPLICATE_PENDING": ("Two pending items are one hull / one firm / one cargo", "IntakeService dedupe on raise and reweigh"),
    "QUEUE_ALREADY_SETTLED": ("Answered or made moot by the automatic pass before review", "reconcile / reweigh timing"),
    "QUEUE_NOTHING_NEW": ("Everything the item says is already on file", "aggregate compare before raising"),
    # the web lookup
    "LOOKUP_CORROBORATED_CREATE": ("Lookup corroborated, IMO not on file: create her with it", "auto-create rule (needs a decision)"),
    "LOOKUP_IMO_ON_FILE": ("Lookup IMO already on a hull here: link", "IntakeService.reconcileIdentifiedHulls"),
    "LOOKUP_AGREES_RECORD": ("Corroborated lookup agrees with the record against the email", "VesselReviewPolicy: minor / auto-keep"),
    "LOOKUP_AGREES_EMAIL": ("Corroborated lookup agrees with the email against the record", "VesselReviewPolicy: pre-tick / auto-accept"),
    "LOOKUP_WRONG_CANDIDATE": ("Lookup hit contradicts size / age / type", "LookupMatcher"),
    "LOOKUP_NO_MATCH": ("No usable lookup; real particulars", "lookup provider coverage"),
    "LOOKUP_TYPE_UNUSED": ("Lookup knows the type the email did not give", "VesselTypes.canonical on the lookup's type"),
    "RENAME_IDENTIFIED": ("Rename on a hull identified by IMO, lookup carries the new name", "auto-accept rename (needs a decision)"),
    # trust
    "TRUST_OWN_FLEET": ("Sender is the hull's owner / manager reporting their own fleet", "VesselReviewPolicy sender weight"),
    "TRUST_NO_EVIDENCE": ("Nothing corroborates either side; kept the record", "nothing - conservative default"),
    # companies
    "COMPANY_NOT_CORRESPONDENT": ("Sender is a provider / system / notification, not a market firm", "COMPANY_DETAILS raise filter"),
    "COMPANY_SUFFIX_ONLY": ("Name differs by legal form, country, city or branch only", "CompanyMatcher.sameFirm"),
    "COMPANY_MATCH_MISSED": ("Unambiguous domain / contact match asked as 'similar or create'", "CompanyMatcher"),
    "COMPANY_NEW_REAL": ("A real new correspondent", "nothing - correct question"),
    "COMPANY_UPDATE_REAL": ("A real change to a firm on file (new person, phone, city)", "minor/major split in CompanyStyleIntake"),
    # cargoes
    "CARGO_SAME_ENQUIRY": ("Probable merge that is the same enquiry", "CargoMatcher anchors"),
    "CARGO_DIFFERENT": ("Probable merge that is a different cargo", "CargoMatcher anchors"),
    # a person's call
    "DECIDED_JUDGEMENT": ("Settled on judgement no evidence rule could give", "nothing - what review is for"),
    "OTHER": ("Anything else - say what in the reason", "triage the reason text"),
}
