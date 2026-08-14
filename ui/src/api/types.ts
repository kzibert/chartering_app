// TypeScript mirrors of the API DTOs (field names must match the JSON exactly).

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface VesselResponse {
  id: number;
  name: string;
  imoNumber?: string;
  deadweightTonnage?: number;
  deadweightCargoCapacity?: number;
  grainCapacityM3?: number;
  baleCapacityM3?: number;
  maximumDraft?: number;
  yearBuilt?: number;
  vesselType?: string;
  flag?: string;
  ownerId?: number;
  ownerName?: string;
  notes?: string;
  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  confirmNotes?: string;
  banned: boolean;
  legacy: boolean;
}

/** How a company relates to a vessel. One role per company per vessel. */
export type VesselCompanyRole = 'owner' | 'exclusive_broker' | 'broker';

export interface VesselCompanyLinkResponse {
  companyId: number;
  companyName: string;
  cityName?: string;
  role: VesselCompanyRole;
  notes?: string;
}

/** A vessel a company is attached to, and in what capacity. */
export interface CompanyVesselResponse {
  vessel: VesselResponse;
  role: VesselCompanyRole;
}

export interface VesselDetailResponse {
  vessel: VesselResponse;
  /** the owner specifically — the company a circular would reach */
  owner?: CompanyResponse;
  ownerContacts: ContactResponse[];
  /** every company on the vessel, owner and brokers alike */
  links: VesselCompanyLinkResponse[];
}

export interface VesselRequest {
  name: string;
  imoNumber?: string;
  deadweightTonnage?: number;
  deadweightCargoCapacity?: number;
  grainCapacityM3?: number;
  baleCapacityM3?: number;
  maximumDraft?: number;
  yearBuilt?: number;
  vesselType?: string;
  flag?: string;
  ownerId?: number;
  notes?: string;
}

export interface CompanyResponse {
  id: number;
  name: string;
  shipowner: boolean;
  charterer: boolean;
  broker: boolean;
  agent: boolean;
  /** one-person business; set by hand, never inferred from the data */
  solo: boolean;
  cityName?: string;
  notes?: string;
  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  confirmNotes?: string;
  banned: boolean;
  legacy: boolean;
  /** derived: the company has emails, but every one is flagged not working */
  noWorkingEmail: boolean;
}

export interface CompanyDetailResponse {
  company: CompanyResponse;
  contacts: ContactResponse[];
  vessels: CompanyVesselResponse[];
}

export interface CompanyRequest {
  name: string;
  shipowner: boolean;
  charterer: boolean;
  broker: boolean;
  agent: boolean;
  solo: boolean;
  cityName?: string;
  notes?: string;
}

export interface PersonResponse {
  id: number;
  fullName: string;
  title?: string;
  greetingName?: string;
  companyId?: number;
  companyName?: string;
  notes?: string;
  legacy: boolean;
}

/** One row of the People page: the person plus their emails and phones. */
export interface PersonDetailResponse {
  person: PersonResponse;
  contacts: ContactResponse[];
}

export interface PersonRequest {
  fullName: string;
  title?: string;
  greetingName?: string;
  companyId?: number;
  notes?: string;
}

export interface ContactResponse {
  id: number;
  personId?: number;
  personName?: string;
  title?: string;
  greetingName?: string;
  companyId?: number;
  companyName?: string;
  contactKind: string; // 'email' | 'phone'
  contactValue: string;
  notes?: string;
  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  confirmNotes?: string;
  banned: boolean;
  legacy: boolean;
  /** the company's default contact of this kind (one main email + one main phone per company) */
  main: boolean;
  /** false = dead/bounced; excluded from bulk collection and from campaign sends */
  working: boolean;
  /**
   * Flagged for use in circulations. Any number per company/person, unlike `main`.
   * Bulk collection takes circ addresses when a person has any, else their main one,
   * else all their working ones.
   */
  circ: boolean;
}

export interface ContactRequest {
  personId?: number;
  companyId?: number;
  contactKind: string;
  contactValue: string;
  notes?: string;
}

export interface ConfirmRequest {
  confirmedBy?: string;
  confirmNotes?: string;
}

export interface LookupResponse {
  id: number;
  name: string;
}

/* ---------------- circulation lists ---------------- */

/**
 * One address on a circulation list. The mail-merge fields are a snapshot taken when the
 * address was collected and are editable per list, so tuning a greeting for one circular
 * never writes back to the person's record.
 */
export interface CirculationListEntry {
  id: number;
  /** the contact it came from, or absent for a hand-typed row / deleted contact */
  contactId?: number;
  email: string;
  personId?: number;
  personName?: string;
  greetingName?: string;
  title?: string;
  companyId?: number;
  companyName?: string;
}

export interface CirculationList {
  id: number;
  /** absent on the draft — the unnamed "current list" every tab collects into */
  name?: string;
  draft: boolean;
  notes?: string;
  entryCount: number;
  /** populated only by the single-list endpoints; the picker gets counts alone */
  entries?: CirculationListEntry[];
  createdAt?: string;
  updatedAt?: string;
}

/** Body for adding addresses to a list. Only the address is required. */
export interface CirculationListEntryRequest {
  email: string;
  contactId?: number;
  personId?: number;
  personName?: string;
  greetingName?: string;
  title?: string;
  companyId?: number;
  companyName?: string;
}

export interface CirculationListRequest {
  name: string;
  notes?: string;
}

// Query param shapes for list endpoints.
export interface PageParams {
  page?: number;
  size?: number;
  sort?: string; // e.g. "name,asc"
}

export interface VesselFilter extends PageParams {
  name?: string;
  imoNumber?: string;
  minDwt?: number;
  maxDwt?: number;
  minDwcc?: number;
  maxDwcc?: number;
  minGrain?: number;
  maxGrain?: number;
  minBale?: number;
  maxBale?: number;
  minDraft?: number;
  maxDraft?: number;
  minYear?: number;
  maxYear?: number;
  vesselType?: string[];
  flag?: string[];
  /** matches vessels this company is on in any role, owner or broker */
  companyId?: number;
  companyName?: string;
  confirmed?: boolean;
  includeBanned?: boolean;
  legacy?: boolean;
}

export interface CompanyFilter extends PageParams {
  name?: string;
  city?: string;
  /** matches a person's full name or greeting name */
  personName?: string;
  shipowner?: boolean;
  charterer?: boolean;
  broker?: boolean;
  agent?: boolean;
  confirmed?: boolean;
  regionId?: number;
  portId?: number;
  tonnageCategoryId?: number;
  includeBanned?: boolean;
  legacy?: boolean;
  /** true = only companies whose every email is flagged not working */
  noWorkingEmail?: boolean;
}

/** People page search. The contact criteria must all be met by the same contact. */
export interface PeopleFilter extends PageParams {
  name?: string;
  companyId?: number;
  contactValue?: string;
  contactKind?: string;
  confirmed?: boolean;
  includeBanned?: boolean;
  legacy?: boolean;
}

export interface ContactFilter extends PageParams {
  kind?: string;
  value?: string;
  companyId?: number;
  personId?: number;
  confirmed?: boolean;
  includeBanned?: boolean;
  legacy?: boolean;
}

/* ---------------- circulars ---------------- */

export type CampaignState =
  | 'IDLE'
  | 'RUNNING'
  | 'COMPLETED'
  | 'COMPLETED_WITH_ERRORS'
  | 'CANCELLED'
  | 'ABORTED';

/** Mail-merge fields sent per recipient — mirrors EmailListEntry. */
export interface CampaignRecipient {
  email: string;
  contactId?: number;
  greetingName?: string;
  personName?: string;
  title?: string;
  companyName?: string;
}

export interface CampaignRequest {
  subject: string;
  htmlBody: string;
  recipients: CampaignRecipient[];
  /** Omitted/null means send with no footer — it does not fall back to the default. */
  footerId?: number | null;
  /** Recorded in history so a run can be traced back to the list it came from. */
  listId?: number | null;
}

export interface CampaignStatus {
  state: CampaignState;
  running: boolean;
  subject?: string;
  total: number;
  sent: number;
  failed: number;
  skipped: number;
  currentEmail?: string;
  startedAt?: string;
  finishedAt?: string;
  etaSeconds?: number;
  lastError?: string;
  message?: string;
}

export interface CampaignConfig {
  enabled: boolean;
  ready: boolean;
  missingSettings?: string[];
  smtpHost?: string;
  smtpPort: number;
  username?: string;
  fromAddress?: string;
  fromName?: string;
  replyTo?: string;
  /** Gap between messages is random in [minDelayMs, maxDelayMs] — never fixed. */
  minDelayMs: number;
  maxDelayMs: number;
  maxRecipientsPerCampaign: number;
  unsubscribeConfigured: boolean;
}

/* ---------------- circulation history ---------------- */

/** How one address fared in a run. Skipped ones were never mailed. */
export type CirculationRecipientStatus =
  | 'SENT'
  | 'FAILED'
  | 'PENDING'
  | 'SKIPPED_DUPLICATE'
  | 'SKIPPED_NOT_WORKING';

/** One line of the History dropdown. */
export interface CirculationRun {
  id: number;
  subject: string;
  listName?: string;
  footerName?: string;
  state: CampaignState;
  total: number;
  sent: number;
  failed: number;
  skipped: number;
  startedAt: string;
  finishedAt?: string;
  message?: string;
}

export interface CirculationRunRecipient {
  id: number;
  email: string;
  contactId?: number;
  personId?: number;
  personName?: string;
  greetingName?: string;
  title?: string;
  companyId?: number;
  companyName?: string;
  status: CirculationRecipientStatus;
  attempts: number;
  error?: string;
  sentAt?: string;
}

export interface CirculationRunDetail {
  run: CirculationRun;
  /** the circular before the merge — still carrying its {{placeholders}} */
  composedHtml: string;
  fromAddress?: string;
  fromName?: string;
  replyTo?: string;
  lastError?: string;
  recipients: CirculationRunRecipient[];
}

/** Exactly what one recipient received, replayed from the run's stored merge. */
export interface CirculationMessage {
  email: string;
  personName?: string;
  subject: string;
  html: string;
  /** the plain-text alternative that actually went out alongside the HTML */
  text: string;
}

/* ---------------- settings ---------------- */

export interface CirculationSettingsRequest {
  /** Envelope From — must be the authenticated mailbox or a verified alias. */
  fromAddress: string;
  /** Display name recipients see; blank sends the bare address. */
  fromName?: string;
  smtpHost: string;
  smtpPort: number;
  /** The gap between two messages is random in [min, max] — never fixed. */
  minDelayMs: number;
  maxDelayMs: number;
  maxRecipientsPerCampaign: number;
}

export interface CirculationSettings extends CirculationSettingsRequest {
  /** true when any value differs from the configured default */
  customised: boolean;
  /** what Reset restores — absent on the nested defaults block itself */
  defaults?: CirculationSettingsRequest;
}

/* ---------------- circular templates & footers ---------------- */

export interface EmailTemplateRequest {
  name: string;
  subject?: string;
  bodyHtml: string;
}

export interface EmailTemplateResponse {
  id: number;
  name: string;
  subject?: string;
  bodyHtml: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface EmailFooterRequest {
  name: string;
  html: string;
  defaultFooter: boolean;
}

export interface EmailFooterResponse {
  id: number;
  name: string;
  html: string;
  defaultFooter: boolean;
  createdAt?: string;
  updatedAt?: string;
}
