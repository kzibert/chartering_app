import { client } from './client';

/**
 * Ask the desk: a question answered from the desk's own mail, board posts, cargoes and vessel
 * positions, with the passages the answer was written from.
 *
 * A local-only feature — it needs the parser and the embedding server, and every endpoint here
 * except `status` answers 404 when it is off. `status` is what the app asks before deciding
 * whether the page is in the navigation at all.
 */

/**
 * Which table a passage came from. MAIL and POST are the two the reader may open the original of;
 * CARGO and POSITION are rows the Cargoes and Open Fleet tabs show, with no route to a single one.
 */
export type AskSourceKind = 'MAIL' | 'POST' | 'CARGO' | 'POSITION';

export interface AskSource {
  /** The number the answer writes in brackets: [n] is this source. */
  n: number;
  kind: AskSourceKind;
  /** The record's id on the desk: a mail message, a board post, a cargo or a position. */
  id: number;
  title?: string;
  /** ISO date shown beside the passage, where the record has one. */
  date?: string;
  /** The start of the passage, on one line. */
  snippet?: string;
}

export interface AskResponse {
  /** The answer, with citations as [n]. */
  answer: string;
  /** Passages in citation order. Empty when nothing on the desk matched. */
  sources: AskSource[];
  /** The model that wrote the answer, where one was asked. */
  model?: string;
}

export interface AskStatus {
  enabled: boolean;
  /** The embedding model the desk's text is indexed with, where the feature is on. */
  model?: string;
}

export interface AskIndexStatus {
  /** The embedding model the index is built with. A change here makes everything pending again. */
  model?: string;
  /** Sources with a current index entry for this model. */
  indexedSources: number;
  /** Sources that need indexing: new, or changed since they were indexed. */
  pendingSources: number;
  running: boolean;
  /** Sources indexed so far in the current run, and how many the run set out to do. */
  done: number;
  total: number;
  /** Why the last run stopped early, if it did. */
  lastError?: string;
  lastFinishedAt?: string;
}

export const askApi = {
  status: () => client.get<AskStatus>('/ask/status').then((r) => r.data),

  /**
   * `quiet` because the page words its own 409 and 503 under the question, where the reader is
   * looking. The tray would say the same thing a second time, further from the question.
   */
  ask: (question: string) =>
    client
      .post<AskResponse>('/ask', { question }, { quiet: true })
      .then((r) => r.data),

  indexStatus: () => client.get<AskIndexStatus>('/ask/index').then((r) => r.data),

  startIndex: () => client.post<AskIndexStatus>('/ask/index').then((r) => r.data),
};
