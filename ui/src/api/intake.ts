import { client, cleanParams } from './client';
import type { FeedSource } from './feed';
import type {
  SourceKind,
  CargoRequest,
  CompanyRequest,
  IntakeItemSourceResponse,
  PageResponse,
  VesselLookupResponse,
  VesselPositionRequest,
  VesselRequest,
  VesselResponse,
} from './types';

// The lookup shapes live in types.ts, because the vessel's own record shows the same card.
// Re-exported here so the Intake screens keep importing them from the module they read as
// belonging to.
export type {
  IntakeItemSourceResponse,
  LookupProposal,
  VesselLookupResponse,
  VesselParticulars,
} from './types';

/**
 * Intake: incoming mail read by the local model into cargoes and open positions.
 *
 * A local-only feature — PARSER_ENABLED is false on the hosted deployment, and every
 * endpoint here except `status` answers 404 when it is off. `status` is what the app asks
 * before deciding whether the tab exists at all.
 *
 * What lands on its own and what waits is the whole shape of this screen: a position for a
 * hull already on file and a cargo nothing else looks like are written straight through,
 * because both only add. Anything that would change what a person put there stops and
 * becomes an item here.
 */

/** Which question an item asks. The four want quite different answers. */
export type IntakeItemKind =
  | 'NEW_VESSEL'
  | 'VESSEL_FIELDS'
  | 'CARGO_MERGE'
  | 'COMPANY_DETAILS';

export type IntakeItemStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED';

export type ParseStatus = 'PARSED' | 'FAILED' | 'SKIPPED' | 'IGNORED';

/**
 * ACCEPT does the proposed thing; ALTERNATIVE does the other one and also writes — keeping
 * a cargo separate creates it, linking a new vessel files the position against the hull you
 * chose. Only DISCARD writes nothing.
 */
export type IntakeAction = 'ACCEPT' | 'ALTERNATIVE' | 'DISCARD';

/** One field the database and the email disagree about, as the review table prints it. */
export interface FieldDiff {
  field: string;
  label: string;
  current?: string;
  incoming?: string;
}

/** A hull whose particulars resemble the one being described — the third matching tier. */
export interface VesselSuggestion {
  vesselId: number;
  name?: string;
  imoNumber?: string;
  /** Why it is being suggested, in figures: "DWT 28,500 against 28,400, built 2003". */
  reason?: string;
}

/**
 * The same shortlist with each hull's record attached, as the detail call sends it.
 *
 * The identity half comes out of the payload — it is a record of the search that ran, and the
 * figures the comparison weighed on the day it weighed them. `vessel` is read from the fleet
 * now, so a deadweight filled in while the item waited is the one on screen. Absent where she
 * has been deleted since, which leaves the row explaining itself rather than vanishing.
 */
export interface IntakeSuggestion extends VesselSuggestion {
  vessel?: VesselResponse;
}

/**
 * The kind-specific half of an item.
 *
 * One optional-everything interface rather than a discriminated union: the payload is stored
 * as text on the server precisely so its shape can move, and a union would make an item
 * raised by an older build a type error rather than something the drawer can explain.
 */
export interface IntakePayload {
  /** NEW_VESSEL / VESSEL_FIELDS: the reading, whole. */
  vessel?: Record<string, unknown>;
  /** NEW_VESSEL: what was looked for and found nothing. */
  searchedBy?: string;
  suggestions?: VesselSuggestion[];
  /** VESSEL_FIELDS. */
  vesselId?: number;
  vesselName?: string;
  /**
   * How it was identified. On VESSEL_FIELDS: IMO, NAME, EX_NAME or LOOKUP_IMO. On
   * COMPANY_DETAILS: email, name or phone, and absent when nothing identified the firm.
   */
  matchedBy?: string;
  diffs?: FieldDiff[];
  /** Fields that were empty and have already been written — no decision needed. */
  filled?: string[];
  /** CARGO_MERGE. */
  cargo?: Record<string, unknown>;
  candidateId?: number;
  candidateLabel?: string;
  reasons?: string[];
  wouldFill?: string[];
  differing?: FieldDiff[];
  /** COMPANY_DETAILS: the signature, in the shape the paste review already renders. */
  draft?: PasteCompanyDraft;
  companyId?: number;
  companyName?: string;
  /** What there is to decide, in the words the queue row prints. */
  changes?: string[];
  /**
   * The comparison against the firm as it stands, worked out when the drawer opened rather
   * than when the item was raised. Absent on an item about a firm not on file, and on an
   * answered one, which keeps what it was decided against.
   */
  comparison?: IntakePasteCompanyComparison;
}

export interface IntakeItemResponse {
  id: number;
  kind: IntakeItemKind;
  status: IntakeItemStatus;
  /** The ship or the commodity, as the email spelled it. */
  subjectLabel?: string;
  /** One line rendered from the payload by the server, so both layouts word it the same. */
  summary?: string;
  vesselId?: number;
  cargoId?: number;
  /** COMPANY_DETAILS: the firm it is about, when it is one already on file. */
  companyId?: number;
  mailMessageId?: number;
  /**
   * Which door it came in through, and the post where it was a board rather than the mailbox.
   *
   * The sender, subject and date fields are filled either way — a post's title stands in for
   * the subject and the board's name for the sender — so a row renders without knowing which.
   */
  sourceKind?: SourceKind;
  feedItemId?: number;
  feedSourceName?: string;
  feedUrl?: string;
  fromAddress?: string;
  fromName?: string;
  /** The company the mail sync resolved the sender to — what a link is offered against. */
  senderCompanyId?: number;
  senderCompanyName?: string;
  mailSubject?: string;
  receivedAt?: string;
  payload?: IntakePayload;
  /** What an outside source found. Detail call only — the list never carries it. */
  lookup?: VesselLookupResponse;
  /**
   * The NEW_VESSEL shortlist with each hull's record attached. Detail call only.
   *
   * `payload.suggestions` carries the same hulls as bare names and reasons and is what an
   * item raised by an older build has; this is that list joined to the fleet. Read this and
   * fall back to the payload — the fallback is what keeps such an item reviewable.
   */
  suggestions?: IntakeSuggestion[];
  /**
   * Every email that raised this item, newest first. Detail call only.
   *
   * Always at least one: the arrival that first raised it is a source like any other. More
   * than one means the same hull arrived again before anybody reviewed her.
   */
  sources?: IntakeItemSourceResponse[];
  createdAt?: string;
  resolvedAt?: string;
  resolvedBy?: string;
  resolutionNote?: string;
}

export interface ApplyLookupRequest {
  fields: string[];
  /** Only needed on a NEW_VESSEL item, where no record exists until it has been accepted. */
  vesselId?: number;
}

export interface LinkSenderRequest {
  /** owner, exclusive_broker or broker. */
  role: string;
  /**
   * Which sender to attach, for an item several emails raised. Must be one of the item's own
   * senders — attaching an unrelated firm is a decision about the ship, not about this email,
   * and belongs on her record. Absent means the arrival that first raised the item.
   */
  companyId?: number;
  notes?: string;
}

export interface IntakeResolveRequest {
  action: IntakeAction;
  /** VESSEL_FIELDS: which differences to accept. Left out means all of them. */
  fields?: string[];
  /** NEW_VESSEL with ALTERNATIVE: the hull this position belongs to. */
  vesselId?: number;
  note?: string;
}

export interface ParsedEmailResponse {
  id: number;
  mailMessageId?: number;
  /**
   * Which door it came in through, and the post where it was a board rather than the mailbox.
   *
   * The sender, subject and date fields are filled either way — a post's title stands in for
   * the subject and the board's name for the sender — so a row renders without knowing which.
   */
  sourceKind?: SourceKind;
  feedItemId?: number;
  feedSourceName?: string;
  feedUrl?: string;
  status: ParseStatus;
  /** The model's own classification: cargo_offer, vessel_opening, mixed, other. */
  emailType?: string;
  fromAddress?: string;
  fromName?: string;
  subject?: string;
  receivedAt?: string;
  positionsApplied: number;
  cargoesApplied: number;
  itemsRaised: number;
  modelName?: string;
  durationMs?: number;
  promptChars?: number;
  attempts: number;
  error?: string;
  parsedAt?: string;
  /** Only on the detail call — the model's whole answer, verbatim. */
  rawJson?: string;
}

export interface IntakeStatusResponse {
  enabled: boolean;
  /** Everything below is absent when the feature is off. */
  reachable?: boolean;
  modelUrl?: string;
  reachabilityError?: string;
  running?: boolean;
  pendingItems?: number;
  acceptedItems?: number;
  rejectedItems?: number;
  unparsed?: number;
  /** Posts off the open boards waiting to be read, counted apart from the mail. */
  unparsedPosts?: number;
  /** Enabled sources marked to be read in. 0 is what the Sources card explains. */
  intakeSources?: number;
  parsedTotal?: number;
  failedTotal?: number;
  sweepIntervalMinutes?: number;
  sweepBatchSize?: number;
  /** How far back unparsed mail is fetched from, in days. 0 = no limit. */
  sweepMaxAgeDays?: number;
  lastSweepAt?: string;
  /** Absent when the interval is 0 — the timer is off and the button is the only way in. */
  nextSweepAt?: string;
  lastSweepSummary?: string;
  warnings?: string[];
}

export interface SweepResponse {
  running: boolean;
  read?: number;
  failed?: number;
  skipped?: number;
  positions?: number;
  cargoes?: number;
  items?: number;
  /** How many of `read` came off a board rather than out of the mailbox. */
  posts?: number;
  /** "Nothing to read" and "could not read" look identical in a count of zero. */
  unreachable?: boolean;
  message?: string;
  finishedAt?: string;
}

export interface ParserSettingsResponse {
  sweepIntervalMinutes: number;
  sweepBatchSize: number;
  /** How far back unparsed mail is fetched from, in days. 0 = no limit. */
  sweepMaxAgeDays: number;
  defaultSweepIntervalMinutes: number;
  defaultSweepBatchSize: number;
  defaultSweepMaxAgeDays: number;
}

export interface ParserSettingsRequest {
  sweepIntervalMinutes?: number;
  sweepBatchSize?: number;
  /** How far back unparsed mail is fetched from, in days. 0 = no limit. */
  sweepMaxAgeDays?: number;
}

/** Who has told us about a cargo — one row per arrival, kept through a merge. */
export interface CargoSourceResponse {
  id: number;
  companyId?: number;
  companyName?: string;
  personId?: number;
  personName?: string;
  fromAddress?: string;
  mailMessageId?: number;
  /**
   * Which door it came in through, and the post where it was a board rather than the mailbox.
   *
   * The sender, subject and date fields are filled either way — a post's title stands in for
   * the subject and the board's name for the sender — so a row renders without knowing which.
   */
  sourceKind?: SourceKind;
  feedItemId?: number;
  feedSourceName?: string;
  feedUrl?: string;
  mailSubject?: string;
  reportedAt?: string;
  notes?: string;
}

/* ---------------- pasted text ---------------- */

/**
 * Text pasted into Intake to be read. Reading writes nothing: every part comes back as a
 * draft in the shape the ordinary forms send, and is accepted through one of them.
 */
export interface IntakePasteRequest {
  text: string;
  subject?: string;
  receivedAt?: string;
}

export interface PasteDuplicateHint {
  cargoId: number;
  commodity: string;
  reasons: string[];
}

export interface PasteCargoDraft {
  cargo: CargoRequest;
  /** The charterer's name when it named no company on file. */
  chartererAsWritten?: string;
  /** A live cargo this looks like — saving would make a second record of it. */
  duplicate?: PasteDuplicateHint;
}

export interface PasteVesselMatch {
  vesselId: number;
  name: string;
  imoNumber?: string;
  /** IMO, NAME or EX_NAME */
  how: string;
}

export interface PasteVesselSuggestion {
  vesselId: number;
  name: string;
  imoNumber?: string;
  reason: string;
}

export interface PasteVesselDraft {
  vessel: VesselRequest;
  match?: PasteVesselMatch;
  suggestions?: PasteVesselSuggestion[];
  /** Absent when the text did not say where she opens. vesselId is the match's, if any. */
  position?: VesselPositionRequest;
}

/** Strongest first: an address, the name, a phone are identity; a domain or a likeness is not. */
export type PasteMatchHow = 'email' | 'name' | 'phone' | 'domain' | 'similar';

export interface PasteCompanyMatch {
  companyId: number;
  name: string;
  city?: string;
  country?: string;
  how: PasteMatchHow;
  reasons: string[];
}

export interface PastePersonDraft {
  fullName: string;
  title?: string;
  jobTitle?: string;
  /** On an accept: the person on file this is, whose details become these. */
  existingPersonId?: number;
}

export interface PasteContactDraft {
  kind: 'email' | 'phone';
  value: string;
  label?: string;
  /** Which person, by fullName; absent for a company-wide address. */
  personName?: string;
  /** On an accept: the contact on file this is, whose label and owner become these. */
  existingContactId?: number;
}

export interface PasteCompanyDraft {
  company: CompanyRequest;
  address?: string;
  people: PastePersonDraft[];
  contacts: PasteContactDraft[];
  matches: PasteCompanyMatch[];
}

export interface IntakePasteDraft {
  type?: string;
  summary?: string;
  /** False when the model is off or not answering — then only `company` was read. */
  modelRead: boolean;
  modelError?: string;
  cargoes: PasteCargoDraft[];
  vessels: PasteVesselDraft[];
  company?: PasteCompanyDraft;
}

/** Fields to overwrite on a company on file; absent leaves one alone. Notes are appended. */
export interface PasteCompanyChanges {
  name?: string;
  cityName?: string;
  country?: string;
  website?: string;
  notes?: string;
}

export interface IntakePasteCompanyRequest {
  companyId?: number;
  company?: CompanyRequest;
  companyChanges?: PasteCompanyChanges;
  people: PastePersonDraft[];
  contacts: PasteContactDraft[];
}

export interface IntakePasteCompanyResponse {
  companyId: number;
  companyName: string;
  created: boolean;
  companyFieldsUpdated: number;
  peopleAdded: number;
  peopleUpdated: number;
  contactsAdded: number;
  contactsUpdated: number;
  /** Addresses and numbers the company already had, by value. */
  skipped?: string[];
}

export interface PasteComparisonField {
  field: keyof PasteCompanyChanges;
  label: string;
  current?: string;
  parsed: string;
}

export interface PastePersonOnFile {
  personId: number;
  fullName: string;
  title?: string;
  jobTitle?: string;
}

/** Index-aligned with the people sent. */
export interface PastePersonRow {
  existingPersonId?: number;
  /** `surname` is surname and first initial — a suggestion, and the screen says so. */
  matchedBy?: 'name' | 'surname';
}

/** Index-aligned with the contacts sent. */
export interface PasteContactRow {
  existingContactId?: number;
  currentLabel?: string;
  currentPersonName?: string;
}

export interface IntakePasteCompanyComparison {
  companyId: number;
  companyName: string;
  fields: PasteComparisonField[];
  peopleOnFile: PastePersonOnFile[];
  people: PastePersonRow[];
  contacts: PasteContactRow[];
}

export interface IntakeItemFilter {
  kind?: IntakeItemKind;
  status?: IntakeItemStatus;
  page?: number;
  size?: number;
  sort?: string;
}

export const intakeApi = {
  status: () => client.get<IntakeStatusResponse>('/intake/status').then((r) => r.data),

  /**
   * Read pasted text into drafts. Writes nothing. A model read of a long text takes a while,
   * so this waits longer than an ordinary call before giving up.
   */
  paste: (body: IntakePasteRequest) =>
    client
      .post<IntakePasteDraft>('/intake/paste', body, { timeout: 180_000 })
      .then((r) => r.data),

  /** Set a pasted company against the one on file it was matched to. Writes nothing. */
  comparePastedCompany: (body: IntakePasteCompanyRequest) =>
    client
      .post<IntakePasteCompanyComparison>('/intake/paste/company/compare', body)
      .then((r) => r.data),

  /** Create the company, or add to and update the one on file — one transaction. */
  pasteCompany: (body: IntakePasteCompanyRequest) =>
    client.post<IntakePasteCompanyResponse>('/intake/paste/company', body).then((r) => r.data),

  items: (filter: IntakeItemFilter) =>
    client
      .get<PageResponse<IntakeItemResponse>>('/intake/items', { params: cleanParams(filter) })
      .then((r) => r.data),

  item: (id: number) =>
    client.get<IntakeItemResponse>(`/intake/items/${id}`).then((r) => r.data),

  resolve: (id: number, body: IntakeResolveRequest) =>
    client.post<IntakeItemResponse>(`/intake/items/${id}/resolve`, body).then((r) => r.data),

  /** Field name -> the label the vessel's own edit form uses, so the two screens agree. */
  vesselFields: () =>
    client.get<Record<string, string>>('/intake/vessel-fields').then((r) => r.data),

  /** Run one search now, replacing whatever was found before. */
  lookup: (id: number) =>
    client.post<IntakeItemResponse>(`/intake/items/${id}/lookup`).then((r) => r.data),

  /** Write the ticked figures from the lookup onto the vessel, in their own change set. */
  applyLookup: (id: number, body: ApplyLookupRequest) =>
    client.post<IntakeItemResponse>(`/intake/items/${id}/apply-lookup`, body).then((r) => r.data),

  /** Attach the company that sent the email to the vessel, in a chosen capacity. */
  linkSender: (id: number, body: LinkSenderRequest) =>
    client.post<IntakeItemResponse>(`/intake/items/${id}/link-sender`, body).then((r) => r.data),

  /** Starts a sweep and returns at once — a sweep is minutes of GPU. Poll `sweep`. */
  startSweep: () => client.post<SweepResponse>('/intake/sweep').then((r) => r.data),

  sweep: () => client.get<SweepResponse>('/intake/sweep').then((r) => r.data),

  parsed: (params: { status?: ParseStatus; page?: number; size?: number }) =>
    client
      .get<PageResponse<ParsedEmailResponse>>('/intake/parsed', { params: cleanParams(params) })
      .then((r) => r.data),

  parsedDetail: (id: number) =>
    client.get<ParsedEmailResponse>(`/intake/parsed/${id}`).then((r) => r.data),

  /** By the parse row's id, not the message's — which is what makes it work for a post too. */
  reopen: (parsedEmailId: number) =>
    client.post<void>(`/intake/parsed/${parsedEmailId}/reopen`).then((r) => r.data),

  ignoreParsed: (parsedEmailId: number, note?: string) =>
    client
      .post<void>(`/intake/parsed/${parsedEmailId}/ignore`, { note })
      .then((r) => r.data),

  /** The boards read into this queue — feed sources with one flag set. */
  sources: () => client.get<FeedSource[]>('/intake/sources').then((r) => r.data),

  /** Fetch the boards now. Fetching is not parsing: the sweep reads what this stores. */
  fetchSources: (sourceId?: number) =>
    client
      .post<void>('/intake/sources/fetch', undefined, { params: cleanParams({ sourceId }) })
      .then((r) => r.data),

  /**
   * Answer a company question with what was ticked — the same body the paste review sends,
   * through the same service, so a signature is allowed to write exactly the same things
   * whichever screen it was reviewed on.
   */
  acceptCompany: (id: number, body: IntakePasteCompanyRequest) =>
    client
      .post<IntakePasteCompanyResponse>(`/intake/items/${id}/company`, body)
      .then((r) => r.data),

  cargoSources: (cargoId: number) =>
    client.get<CargoSourceResponse[]>(`/intake/cargoes/${cargoId}/sources`).then((r) => r.data),

  settings: () =>
    client.get<ParserSettingsResponse>('/intake/settings').then((r) => r.data),

  updateSettings: (body: ParserSettingsRequest) =>
    client.put<ParserSettingsResponse>('/intake/settings', body).then((r) => r.data),

  resetSettings: () =>
    client.delete<ParserSettingsResponse>('/intake/settings').then((r) => r.data),
};
