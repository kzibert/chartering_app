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
  country?: string;
  /** Bare host as stored — "fednav.com". The UI adds the scheme when it makes a link. */
  website?: string;
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
  country?: string;
  website?: string;
  notes?: string;
}

export interface PersonResponse {
  id: number;
  fullName: string;
  /** honorific printed before the greeting name (Mr., Capt.) — never the job */
  title?: string;
  /** the position held at the company — "Chartering Manager", "Operations" */
  jobTitle?: string;
  greetingName?: string;
  companyId?: number;
  companyName?: string;
  notes?: string;
  legacy: boolean;
  /**
   * No longer works at this company. Every address and number of theirs is then off
   * circulations — left out of collection, and dropped again at send time, so one already
   * sitting on a saved list or the current draft still cannot be mailed. Nothing is
   * deleted: the record stays and past circulations keep pointing at it.
   */
  hasLeft: boolean;
}

/** One row of the People page: the person plus their emails and phones. */
export interface PersonDetailResponse {
  person: PersonResponse;
  contacts: ContactResponse[];
}

export interface PersonRequest {
  fullName: string;
  title?: string;
  jobTitle?: string;
  greetingName?: string;
  companyId?: number;
  notes?: string;
}

export interface ContactResponse {
  id: number;
  personId?: number;
  personName?: string;
  /**
   * The person behind this address has left the company, so it is off circulations. None of
   * the address's own flags says so — the exclusion lives on the person and reaches every
   * address of theirs at once.
   */
  personLeft: boolean;
  title?: string;
  /**
   * The position the person behind this address holds at the company. Read off the person
   * server-side, never stored per address: one human's mobile and two mailboxes all carry
   * the same position. Absent for a company-wide address, which has no person.
   *
   * Edited on the person, not here — the contact form shows it read-only.
   */
  jobTitle?: string;
  /**
   * The greeting to actually use: the contact's own when it has one, else the person's.
   * Everything downstream reads this — the contact row, the circulation list builder, the
   * WhatsApp link. Absent means no greeting is on file, and the merge falls through to the
   * general "Sirs", which is what a company-wide address gets until somebody types one.
   */
  greetingName?: string;
  /**
   * The override as stored, with no fallback applied. Only the edit form wants this:
   * prefilling the field from `greetingName` would show the person's greeting and pin a
   * copy of it onto the contact on the next save.
   */
  ownGreetingName?: string;
  /**
   * This address belongs to the company itself rather than to any person — a chartering@
   * or ops@ desk. Just `personId == null && companyId != null`, derived server-side so
   * every row that wants to label itself reads one field instead of re-deriving it.
   */
  companyWide: boolean;
  companyId?: number;
  companyName?: string;
  contactKind: string; // 'email' | 'phone'
  contactValue: string;
  /**
   * What kind of line this is — Work, Mobile, Direct, Fax. Phones only: an email has no
   * equivalent, so it is absent there rather than carrying a label that means nothing.
   */
  label?: string;
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
  /**
   * Flagged never to be circulated to. Distinct from `working: false` — the address is
   * fine and still the one to write to by hand, it is only bulk mail it is kept out of.
   * The exact opposite of `circ`, so setting either clears the other.
   */
  noCirc: boolean;
  /**
   * This number is on WhatsApp. Recorded by hand — nothing can ask WhatsApp whether a
   * number is registered, so the row offers a wa.me link and the user says what they saw.
   * Phone contacts only.
   */
  hasWhatsapp: boolean;
}

export interface ContactRequest {
  /**
   * Who the address belongs to, or absent for one that belongs to the company itself.
   * At least one of this and `companyId` must be set — the server refuses a contact
   * filed under neither, which no screen could list.
   */
  personId?: number;
  companyId?: number;
  contactKind: string;
  contactValue: string;
  /** Work/Mobile/Direct/Fax. Ignored on an email, which has no such thing. */
  label?: string;
  /** Overrides the person's greeting. Blank falls back to the person's, then to "Sirs". */
  greetingName?: string;
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
  /**
   * The send will drop this row: the person behind the address has left the company. The
   * row stays on the list — a list is a snapshot of a document you prepared — but a row
   * that cannot be mailed and does not say so makes the recipient count a lie.
   */
  personLeft: boolean;
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

/**
 * What a bulk add did. `invalid` rows are values the contact data holds that are not
 * sendable addresses — they are dropped instead of failing the whole add, and named in
 * `invalidEmails` so the contact record can be corrected.
 */
export interface AddEntriesResult {
  added: number;
  skipped: number;
  invalid: number;
  invalidEmails: string[];
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
  /**
   * DWT and DWCC are OR'd with each other, as are grain and bale — the two figures in
   * each pair are rarely both on file, so filling both boxes means "either". A range only
   * matches vessels where that figure is recorded; 0 in the data means unknown.
   */
  minDwt?: number;
  maxDwt?: number;
  minDwcc?: number;
  maxDwcc?: number;
  minGrain?: number;
  maxGrain?: number;
  minBale?: number;
  maxBale?: number;
  /** No minimum: the question is "will it fit", never "is it deep enough". */
  maxDraft?: number;
  /** Oldest acceptable build year — matches that year and younger. */
  yearFrom?: number;
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
  | 'ABORTED'
  /** Stopped by hand with people still to reach; resume carries it on. */
  | 'PAUSED'
  /** Stopped by an API restart mid-send. Resumable in exactly the same way. */
  | 'INTERRUPTED';

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
  /** The history run this progress belongs to — what resume and restart are called with. */
  runId?: number;
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
  /**
   * Which run of the campaign is in flight and how many there are. A list within the
   * per-run cap is run 1 of 1, so these never need a special case.
   */
  batch: number;
  batchCount: number;
  /** True between runs — nothing is being sent until nextBatchAt. */
  paused: boolean;
  nextBatchAt?: string;
  /**
   * This run stopped with people still to reach, so it can be carried on. Only ever true
   * once the send has actually stopped. Status is in-memory, so after an API restart it
   * reports IDLE — ask campaignsApi.resumable() for runs that outlived the process.
   */
  resumable: boolean;
}

/**
 * How circulars leave. `SMTP` hands each message to the user's own mailbox — the circular
 * arrives from a real person, and that mailbox's quota is what is being spent. `BREVO`
 * posts it to Brevo's transactional API, which is faster and puts the provider's
 * reputation on the line instead of the mailbox's.
 */
export type CircularProvider = 'SMTP' | 'BREVO';

export interface CampaignConfig {
  enabled: boolean;
  ready: boolean;
  missingSettings?: string[];
  /** Which flow is sending right now — shown on the Circulars tab beside the pacing. */
  provider: CircularProvider;
  /** The same choice, worded for display: "Mailbox (SMTP)" or "Brevo API". */
  providerLabel: string;
  /** Only meaningful under SMTP. */
  smtpHost?: string;
  smtpPort: number;
  username?: string;
  fromAddress?: string;
  fromName?: string;
  replyTo?: string;
  /** Gap between messages is random in [minDelayMs, maxDelayMs] — never fixed. */
  minDelayMs: number;
  maxDelayMs: number;
  /** Recipients per run; a longer list is sent as several runs of this size. */
  maxRecipientsPerCampaign: number;
  /** Quiet gap between those runs. */
  batchPauseMs: number;
  unsubscribeConfigured: boolean;
}

/* ---------------- circulation history ---------------- */

/** How one address fared in a run. Skipped ones were never mailed. */
export type CirculationRecipientStatus =
  | 'SENT'
  | 'FAILED'
  | 'PENDING'
  | 'SKIPPED_DUPLICATE'
  | 'SKIPPED_NOT_WORKING'
  | 'SKIPPED_NOT_FOR_CIRC'
  | 'SKIPPED_LEFT_COMPANY';

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
  /** Queued and never reached — who a resume would send to. */
  pending: number;
  /** The run stopped with those people still to reach, so it can be carried on. */
  resumable: boolean;
  startedAt: string;
  finishedAt?: string;
  message?: string;
}

/**
 * The day's outgoing volume. The server counts it in *its* local day, so the figure does
 * not change with the reader's browser clock.
 */
/**
 * Today as Brevo reports it — account-wide, not just what this app sent through it.
 *
 * Every field is optional because each can genuinely be unavailable: Brevo may be
 * unreachable (then `error` says why and the numbers are absent), and a plan with purchased
 * credits rather than a daily ceiling has no `remaining` at all. Absent is left absent
 * rather than shown as 0, which would read as "nothing left".
 */
export interface BrevoUsage {
  /**
   * Messages Brevo accepted today, across the whole account.
   *
   * An after-the-fact report, so it trails a running campaign by minutes, and it counts
   * acceptance rather than spend. Read it as "how has today gone", never as an input to
   * arithmetic against `remaining` — that is the mistake `dailyLimit` used to make.
   */
  sent?: number;
  /**
   * Of `sent`, how many were suppressed rather than sent. They cost no allowance, so they are
   * the usual reason `sent` runs ahead of `dailyLimit - remaining`.
   */
  blocked?: number;
  /** What is left of the daily allowance; absent on a plan with no daily cap. Live and exact. */
  remaining?: number;
  /** The plan's ceiling, from `BREVO_DAILY_LIMIT`. Absent on a plan with no daily cap. */
  dailyLimit?: number;
  /** Why the figures are missing, when Brevo could not be reached. */
  error?: string;
}

export interface CirculationToday {
  /** The local day counted, so a tab left open overnight can see the counter roll over. */
  date: string;
  /** Addresses this app mailed today, across every circulation and both flows. */
  sent: number;
  /** How many circulations those messages came from — those that delivered, not those opened. */
  circulations: number;
  /** Of `sent`, how many left from the mailbox over SMTP. */
  viaMailbox: number;
  /** Of `sent`, how many left through the Brevo API. */
  viaBrevo: number;
  /**
   * Brevo's own account-wide figures. Absent when no API key is configured.
   *
   * Worth showing next to `viaBrevo` rather than instead of it: they answer different
   * questions. `viaBrevo` is what this app sent; `brevo.sent` is what the whole account
   * spent, which is what the daily cap is actually enforced against.
   */
  brevo?: BrevoUsage;
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
  /** Recipients per run; a longer list is split into runs of this size. */
  maxRecipientsPerCampaign: number;
  /** Quiet gap between those runs. 0 sends them back to back. */
  batchPauseMs: number;
}

export interface CirculationSettings extends CirculationSettingsRequest {
  /** Which flow sends circulars. Changed with settingsApi.setProvider, not by saving the form. */
  provider: CircularProvider;
  providerLabel: string;
  /**
   * Whether BREVO_API_KEY is present in the environment. The switch is still offered
   * without it — the settings screen is where you would go to find out why it is missing —
   * but the screen says plainly that a send would fail.
   */
  brevoConfigured: boolean;
  /** true when any value differs from the configured default *for this provider* */
  customised: boolean;
  /**
   * What Reset restores — absent on the nested defaults block itself. Provider-dependent:
   * Brevo's pacing baseline is far faster than the mailbox's, because the two are
   * protecting different things.
   */
  defaults?: CirculationSettings;
}

export interface WhatsappSettingsRequest {
  /** Prefilled into wa.me links. Takes the same {{...}} placeholders as a circular. */
  message: string;
}

export interface WhatsappSettings extends WhatsappSettingsRequest {
  /** What Reset restores. */
  defaultMessage: string;
  customised: boolean;
  /** Placeholder name -> what it renders as, for the hint under the field. */
  placeholders: Record<string, string>;
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

/* ---------------- mailbox ---------------- */

/** One row of the message list. The body is fetched only when a message is opened. */
export interface MailMessage {
  id: number;
  fromAddress: string;
  fromName?: string;
  subject?: string;
  snippet?: string;
  sentAt?: string;
  receivedAt: string;
  read: boolean;
  hasAttachments: boolean;
  /** null/absent = the Inbox, i.e. nothing has filed it yet. */
  folderId?: number;
  folderName?: string;
  /** Set when a rule filed it — the answer to "why is this in here?". */
  filedByRuleId?: number;
  /** The folder the mail server has it in — where Zoho's own filters put it. */
  imapFolder?: string;
  companyId?: number;
  companyName?: string;
  personId?: number;
  personName?: string;
  /** The company link was set by hand; no re-link pass will overwrite it. */
  linkManual: boolean;
}

export interface MailMessageDetail {
  message: MailMessage;
  toAddresses?: string;
  ccAddresses?: string;
  /** Sanitized server-side. Absent when the message carried no HTML part. */
  bodyHtml?: string;
  bodyText?: string;
  attachmentNames?: string;
  sizeBytes?: number;
  messageId?: string;
}

/** A folder in the rail. The Inbox is one of these with no id. */
export interface MailFolder {
  id?: number;
  name: string;
  notes?: string;
  sortOrder: number;
  total: number;
  unread: number;
}

/**
 * One folder of the mail server's own tree — a read-only mirror. Flat, with `parentName`
 * pointing at the row above, which the rail assembles into a tree.
 *
 * Two pairs of counts, because they answer different questions: `total`/`unread` is the mail
 * synced into the app, `serverTotal`/`serverUnseen` is what the server says the folder holds.
 * A folder showing 26 there and nothing here has not been reached yet, which otherwise looks
 * exactly like an empty folder.
 */
export interface MailServerFolder {
  /** The IMAP full name — 'INBOX', 'DMARC Reports', 'Brokers/Handy'. The identity. */
  fullName: string;
  displayName: string;
  parentName?: string;
  specialUse?: 'INBOX' | 'SENT' | 'DRAFTS' | 'JUNK' | 'TRASH' | 'ARCHIVE';
  /** False for a branch of the tree that holds no mail of its own. */
  selectable: boolean;
  /** Still listed by the server. False for a folder deleted in Zoho since. */
  present: boolean;
  sortOrder: number;
  total: number;
  unread: number;
  serverTotal?: number;
  serverUnseen?: number;
  lastSyncAt?: string;
  lastStatus?: 'OK' | 'FAILED';
  lastError?: string;
}

export interface MailFolderRequest {
  name: string;
  notes?: string;
  sortOrder?: number;
}

export type MailRuleField = 'FROM' | 'FROM_DOMAIN' | 'TO' | 'SUBJECT' | 'BODY' | 'ANY';
export type MailRuleOperator =
  | 'CONTAINS'
  | 'NOT_CONTAINS'
  | 'EQUALS'
  | 'STARTS_WITH'
  | 'ENDS_WITH';

export interface MailRuleCondition {
  id?: number;
  field: MailRuleField;
  operator: MailRuleOperator;
  value: string;
}

export interface MailRule {
  id: number;
  name: string;
  folderId: number;
  folderName: string;
  enabled: boolean;
  sortOrder: number;
  /** ALL = every condition must match, ANY = at least one. */
  matchType: 'ALL' | 'ANY';
  markRead: boolean;
  conditions: MailRuleCondition[];
}

export interface MailRuleRequest {
  name: string;
  folderId: number;
  enabled: boolean;
  sortOrder?: number;
  matchType: 'ALL' | 'ANY';
  markRead: boolean;
  conditions: MailRuleCondition[];
}

/** What re-running the rules over already-synced mail did. */
export interface MailRuleRun {
  evaluated: number;
  filed: number;
  markedRead: number;
}

export interface MailboxStatus {
  enabled: boolean;
  /** enabled and every credential present — a sync would actually be attempted. */
  configured: boolean;
  /** What is missing, named as the environment variable that supplies it. */
  missingSettings: string[];
  host?: string;
  /** The folder read first on every poll. Every folder in the mailbox is mirrored. */
  folder?: string;
  folderCount: number;
  username?: string;
  syncing: boolean;
  lastSyncAt?: string;
  lastStatus?: 'OK' | 'FAILED';
  lastError?: string;
  lastFetched: number;
  lastStored: number;
  pollIntervalMs: number;
  totalMessages: number;
  unread: number;
}

export interface MailboxFilter extends PageParams {
  /** One box for sender, subject, recipients and the linked company/person. */
  search?: string;
  /** Also scan the message text. Unindexed, hence opt-in — see the Mailbox tab. */
  searchBody?: boolean;
  folderId?: number;
  /** true = mail no app rule or hand has filed. */
  unfiled?: boolean;
  /** One folder on the mail server, and everything nested under it. */
  imapFolder?: string;
  read?: boolean;
  companyId?: number;
  linked?: boolean;
}

/**
 * Which mail a whole-view action applies to: the folder rail's selection and the search box,
 * without the paging or the read/unread narrowing. It is the filter minus the parts that
 * only decide what is on screen right now, which is what makes "mark all read" mean the same
 * thing on page one as on page four.
 */
export type MailboxScope = Pick<
  MailboxFilter,
  'search' | 'searchBody' | 'folderId' | 'unfiled' | 'imapFolder' | 'companyId'
>;

export interface MailLinkRequest {
  companyId?: number;
  personId?: number;
  /** Also record the sender's address as a contact, so later mail links itself. */
  createContact?: boolean;
}
