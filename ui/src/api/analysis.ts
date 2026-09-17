import { client, cleanParams } from './client';
import type { PageResponse } from './types';

/**
 * The analysis workbench: incoming mail kept as training data for a model that reads cargo
 * offers and vessel opening positions.
 *
 * A local-only feature — ANALYSIS_ENABLED is false on the hosted deployment, and every
 * endpoint here except `status` answers 404 when it is off. `status` is what the app asks
 * before deciding whether the tab exists at all.
 */

/** What kind of email a sample is. BOTH is a real answer, not a hedge — see the API. */
export type AnalysisLabel =
  | 'UNLABELLED'
  | 'CARGO_OFFER'
  | 'VESSEL_OPENING'
  | 'BOTH'
  | 'OTHER';

/** How far through review. READY is the only status the export reads. */
export type AnalysisStatus = 'NEW' | 'READY' | 'SKIPPED';

export interface AnalysisSampleResponse {
  id: number;
  /**
   * MAILBOX (captured from synced mail), WEB (off an open board) or PASTED (added by hand).
   *
   * Worth telling apart in a corpus: what arrives by mail is what somebody chose to send this
   * desk, and a model trained only on that has never seen the layouts nobody addressed to us.
   */
  source: 'MAILBOX' | 'WEB' | 'PASTED';
  /** The message it came from, while that message is still in the mailbox. */
  mailMessageId?: number;
  /** The board post it came off, while the fetcher still holds a copy. */
  feedItemId?: number;
  fromAddress?: string;
  fromName?: string;
  subject?: string;
  sentAt?: string;
  receivedAt?: string;
  snippet?: string;
  attachmentNames?: string;
  label: AnalysisLabel;
  status: AnalysisStatus;
  /** Whether a target output has been written — the expensive half of a sample. */
  annotated: boolean;
  bodyChars: number;
  notes?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface AnalysisSampleDetailResponse {
  sample: AnalysisSampleResponse;
  bodyText: string;
  annotation?: string;
}

export interface AnalysisSampleUpdateRequest {
  label?: AnalysisLabel;
  status?: AnalysisStatus;
  /** Must parse as JSON; '' clears it. A field left out is left alone. */
  annotation?: string;
  notes?: string;
}

export interface AnalysisPasteRequest {
  fromAddress?: string;
  fromName?: string;
  subject?: string;
  receivedAt?: string;
  bodyText: string;
  notes?: string;
}

export interface AnalysisCaptureRequest {
  /**
   * Which half to take from. MAILBOX is the default; WEB reads the boards the Feed tab
   * collects. The fields below split by which half they serve, and the ones that do not
   * apply are ignored rather than refused.
   */
  source?: 'MAILBOX' | 'WEB';
  /** MAILBOX only. */
  imapFolder?: string;
  /** MAILBOX only. */
  folderId?: number;
  /** MAILBOX only — scanning every stored body is slow, exactly as on the Mailbox tab. */
  searchBody?: boolean;
  /**
   * WEB only. Blank takes every board marked "Read into Intake" rather than every source
   * there is: those are the ones carrying circulars, and a news feed would contribute
   * articles a cargo-extraction model has nothing to learn from.
   */
  feedSourceId?: number;
  search?: string;
  receivedFrom?: string;
  receivedTo?: string;
  limit?: number;
}

export interface AnalysisCaptureResponse {
  matched: number;
  captured: number;
  alreadyPresent: number;
  skippedEmpty: number;
  limitReached: boolean;
  examples: string[];
}

export interface AnalysisStatusResponse {
  enabled: boolean;
  /** Everything below is absent when the feature is off — nothing is counted, so nothing
      is claimed. */
  totalSamples?: number;
  readySamples?: number;
  byLabel?: Record<string, number>;
  byStatus?: Record<string, number>;
  /** A starting shape per label, so a corpus is annotated consistently. */
  annotationTemplates?: Record<string, string>;
  exportSystemPrompt?: string;
  maxBodyChars?: number;
  maxCapturePerRun?: number;
  mailboxEnabled?: boolean;
  syncedMessages?: number;
  warnings?: string[];
}

export interface AnalysisSampleFilter {
  search?: string;
  label?: AnalysisLabel;
  status?: AnalysisStatus;
  source?: string;
  receivedFrom?: string;
  receivedTo?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const analysisApi = {
  status: () => client.get<AnalysisStatusResponse>('/analysis/status').then((r) => r.data),

  search: (filter: AnalysisSampleFilter) =>
    client
      .get<PageResponse<AnalysisSampleResponse>>('/analysis/samples', {
        params: cleanParams(filter),
      })
      .then((r) => r.data),

  get: (id: number) =>
    client.get<AnalysisSampleDetailResponse>(`/analysis/samples/${id}`).then((r) => r.data),

  update: (id: number, body: AnalysisSampleUpdateRequest) =>
    client
      .patch<AnalysisSampleDetailResponse>(`/analysis/samples/${id}`, body)
      .then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/analysis/samples/${id}`).then((r) => r.data),

  capture: (body: AnalysisCaptureRequest) =>
    client.post<AnalysisCaptureResponse>('/analysis/capture', body).then((r) => r.data),

  paste: (body: AnalysisPasteRequest) =>
    client.post<AnalysisSampleDetailResponse>('/analysis/samples', body).then((r) => r.data),

  /**
   * The training file.
   *
   * Fetched through axios rather than pointed at with a plain link, because the API needs
   * the bearer token and a link cannot carry one. The blob is handed to a temporary <a> so
   * the browser saves it under the stamped filename the server chose — reading that name
   * off Content-Disposition rather than inventing one here keeps the two in step.
   */
  exportJsonl: async () => {
    const res = await client.get('/analysis/export', { responseType: 'blob' });
    const disposition = String(res.headers['content-disposition'] ?? '');
    const named = /filename="?([^"]+)"?/.exec(disposition)?.[1];
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = named ?? 'chartering-training.jsonl';
    a.click();
    URL.revokeObjectURL(url);
  },
};
