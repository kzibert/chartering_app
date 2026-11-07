import { client } from './client';

/**
 * Which optional features this deployment has. Configuration only on the server - no query, no
 * network - so the shell asks once and keeps the answer: none of it changes without a restart.
 */
export interface Capabilities {
  /** The Analysis tab: gathering and labelling training data. */
  analysis: boolean;
  /** An embedding server is configured, so the corpus can be indexed for retrieval. */
  retrieval: boolean;
  /** Intake reads with this deployment's own model, and its timers run. */
  parserLocal: boolean;
  /** Intake may read with a hosted model under the person's own key. */
  parserCloud: boolean;
  /** The Feed tab, its fetching and summaries. */
  feed: boolean;
  /** Looking a hull up on a public ship database. */
  lookup: boolean;
  /** This host cannot open SMTP: replies and circulars go out through Brevo. */
  smtpBlocked: boolean;
}

export const capabilitiesApi = {
  get: () => client.get<Capabilities>('/capabilities').then((r) => r.data),
};
