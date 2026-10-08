import { client } from './client';

/** SERVER: the server's BREVO_API_KEY, which is yours because the server's mailbox is. */
export type BrevoAccountSource = 'SERVER' | 'PERSONAL' | 'NONE';

export interface BrevoAccountResponse {
  source: BrevoAccountSource;
  /** Whether this server can store a key at all (CREDENTIALS_KEY). */
  canStore: boolean;
  /** The key's last four characters; the key itself never comes back. */
  keyHint?: string;
  senderAddress?: string;
  senderName?: string;
}

export interface BrevoAccountRequest {
  /** Blank on a later save keeps the stored one. */
  apiKey?: string;
  /** A sender verified in Brevo. Blank sends as your mailbox address. */
  senderAddress?: string;
  senderName?: string;
}

export const myBrevoApi = {
  get: () => client.get<BrevoAccountResponse>('/me/brevo-account').then((r) => r.data),
  save: (body: BrevoAccountRequest) =>
    client.put<BrevoAccountResponse>('/me/brevo-account', body).then((r) => r.data),
  remove: () => client.delete('/me/brevo-account'),
};
