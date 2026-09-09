import { client, cleanParams } from './client';
import type { PageResponse, VesselLookupResponse } from './types';

// The lookup shapes live in types.ts, because the vessel's own record shows the same card.
// Re-exported here so the Intake screens keep importing them from the module they read as
// belonging to.
export type { LookupProposal, VesselLookupResponse, VesselParticulars } from './types';

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

/** Which question an item asks. The three want quite different answers. */
export type IntakeItemKind = 'NEW_VESSEL' | 'VESSEL_FIELDS' | 'CARGO_MERGE';

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
  mailMessageId?: number;
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
  mailSubject?: string;
  reportedAt?: string;
  notes?: string;
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

  reopen: (mailMessageId: number) =>
    client.post<void>(`/intake/parsed/${mailMessageId}/reopen`).then((r) => r.data),

  ignoreParsed: (mailMessageId: number, note?: string) =>
    client
      .post<void>(`/intake/parsed/${mailMessageId}/ignore`, { note })
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
