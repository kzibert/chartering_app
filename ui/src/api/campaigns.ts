import { client } from './client';
import type { CampaignConfig, CampaignRequest, CampaignStatus } from './types';

export const campaignsApi = {
  config: () => client.get<CampaignConfig>('/campaigns/config').then((r) => r.data),

  placeholders: () =>
    client.get<Record<string, string>>('/campaigns/placeholders').then((r) => r.data),

  /** Returns 202 immediately — sending continues server-side, poll status() for progress. */
  start: (body: CampaignRequest) =>
    client.post<CampaignStatus>('/campaigns', body).then((r) => r.data),

  status: () => client.get<CampaignStatus>('/campaigns/current').then((r) => r.data),

  cancel: () =>
    client.post<CampaignStatus>('/campaigns/current/cancel').then((r) => r.data),

  log: () =>
    client.get<string>('/campaigns/current/log', { responseType: 'text' }).then((r) => r.data),

  test: (to: string, body: CampaignRequest) =>
    client.post<void>('/campaigns/test', body, { params: { to } }).then((r) => r.data),
};
