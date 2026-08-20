import { client } from './client';
import type { CirculationSettings, CirculationSettingsRequest } from './types';

/**
 * Runtime settings. Values live in the database and override the configured defaults from
 * application.yml; deleting them (reset) restores those defaults.
 *
 * Credentials are not here and cannot be set from the UI — MAIL_USERNAME, MAIL_PASSWORD
 * and BREVO_API_KEY stay in the environment.
 */
export const settingsApi = {
  circulation: () =>
    client.get<CirculationSettings>('/settings/circulation').then((r) => r.data),

  update: (body: CirculationSettingsRequest) =>
    client.put<CirculationSettings>('/settings/circulation', body).then((r) => r.data),

  /**
   * Switch between sending from the mailbox over SMTP and sending through Brevo.
   *
   * Its own call rather than a field on the settings form: the pacing shown in that form
   * belongs to whichever provider is in force, so the switch has to land first and the
   * form redraw with the new provider's values. Saving both at once would write the old
   * provider's numbers against the new one.
   */
  setProvider: (useBrevo: boolean) =>
    client
      .put<CirculationSettings>('/settings/circulation/provider', { useBrevo })
      .then((r) => r.data),

  reset: () =>
    client.delete<CirculationSettings>('/settings/circulation').then((r) => r.data),
};
