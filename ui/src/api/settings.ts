import { client } from './client';
import type { CirculationSettings, CirculationSettingsRequest } from './types';

/**
 * Runtime settings. Values live in the database and override the configured defaults from
 * application.yml; deleting them (reset) restores those defaults.
 *
 * SMTP credentials are not here and cannot be set from the UI — MAIL_USERNAME and
 * MAIL_PASSWORD stay in the environment.
 */
export const settingsApi = {
  circulation: () =>
    client.get<CirculationSettings>('/settings/circulation').then((r) => r.data),

  update: (body: CirculationSettingsRequest) =>
    client.put<CirculationSettings>('/settings/circulation', body).then((r) => r.data),

  reset: () =>
    client.delete<CirculationSettings>('/settings/circulation').then((r) => r.data),
};
