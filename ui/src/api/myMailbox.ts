import { client } from './client';

/** SERVER: the mailbox configured in the server's environment, which is yours. */
export type MailAccountSource = 'SERVER' | 'PERSONAL' | 'NONE';

export interface MailAccountResponse {
  source: MailAccountSource;
  /** Whether this server can store a password at all (CREDENTIALS_KEY). */
  canStore: boolean;
  emailAddress?: string;
  displayName?: string;
  imapHost?: string;
  imapPort?: number;
  imapSsl?: boolean;
  smtpHost?: string;
  smtpPort?: number;
  enabled?: boolean;
  passwordSet?: boolean;
}

export interface MailAccountRequest {
  emailAddress: string;
  displayName?: string;
  imapHost: string;
  imapPort: number;
  imapSsl: boolean;
  smtpHost: string;
  smtpPort: number;
  /** Blank on a later save keeps the stored one. */
  password?: string;
  enabled: boolean;
}

export const myMailboxApi = {
  get: () => client.get<MailAccountResponse>('/me/mail-account').then((r) => r.data),
  save: (body: MailAccountRequest) =>
    client.put<MailAccountResponse>('/me/mail-account', body).then((r) => r.data),
  remove: () => client.delete('/me/mail-account'),
};
