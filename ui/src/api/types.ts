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

export interface VesselDetailResponse {
  vessel: VesselResponse;
  owner?: CompanyResponse;
  ownerContacts: ContactResponse[];
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
  vessels: VesselResponse[];
}

export interface CompanyRequest {
  name: string;
  shipowner: boolean;
  charterer: boolean;
  broker: boolean;
  agent: boolean;
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

/**
 * One row in the (client-side only) email list used for mass-mail prep.
 * Keyed by contactId; carries the person/company context + referencing ids so the
 * exported sheet is self-describing.
 */
export interface EmailListEntry {
  contactId: number;
  email: string;
  personId?: number;
  personName?: string;
  greetingName?: string;
  title?: string;
  companyId?: number;
  companyName?: string;
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
  ownerId?: number;
  ownerName?: string;
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
