import { client } from './client';
import type {
  CampaignConfig,
  CampaignRequest,
  CampaignStatus,
  CirculationRun,
} from './types';

export const campaignsApi = {
  config: () => client.get<CampaignConfig>('/campaigns/config').then((r) => r.data),

  placeholders: () =>
    client.get<Record<string, string>>('/campaigns/placeholders').then((r) => r.data),

  /** Returns 202 immediately — sending continues server-side, poll status() for progress. */
  start: (body: CampaignRequest) =>
    client.post<CampaignStatus>('/campaigns', body).then((r) => r.data),

  status: () => client.get<CampaignStatus>('/campaigns/current').then((r) => r.data),

  /** Stops after the message in flight and closes the run. */
  cancel: () =>
    client.post<CampaignStatus>('/campaigns/current/cancel').then((r) => r.data),

  /** Stops after the message in flight and leaves the run open, ready to be resumed. */
  pause: () => client.post<CampaignStatus>('/campaigns/current/pause').then((r) => r.data),

  /**
   * Circulations that stopped with people still to reach — paused, cancelled, aborted, or
   * cut off by an API restart. The queue lives in the database, so this survives a restart
   * even though status() does not.
   */
  resumable: () =>
    client.get<CirculationRun[]>('/campaigns/resumable').then((r) => r.data),

  /** Carries a stopped run on, mailing only the addresses it never reached. */
  resume: (runId: number) =>
    client.post<CampaignStatus>(`/campaigns/runs/${runId}/resume`).then((r) => r.data),

  /** Sends a past circulation again from the top, as a new run of its own. */
  restart: (runId: number) =>
    client.post<CampaignStatus>(`/campaigns/runs/${runId}/restart`).then((r) => r.data),

  log: () =>
    client.get<string>('/campaigns/current/log', { responseType: 'text' }).then((r) => r.data),

  test: (to: string, body: CampaignRequest) =>
    client.post<void>('/campaigns/test', body, { params: { to } }).then((r) => r.data),
};
