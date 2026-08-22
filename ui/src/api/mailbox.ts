import { client, cleanParams } from './client';
import type {
  MailFolder,
  MailFolderRequest,
  MailLinkRequest,
  MailMessage,
  MailMessageDetail,
  MailServerFolder,
  MailRule,
  MailRuleRequest,
  MailRuleRun,
  MailboxFilter,
  MailboxScope,
  MailboxStatus,
  PageResponse,
} from './types';

/** The synced inbox: messages, the folders they are filed into, and the rules that file them. */
export const mailboxApi = {
  search: (filter: MailboxFilter) =>
    client
      .get<PageResponse<MailMessage>>('/mailbox/messages', { params: cleanParams(filter) })
      .then((r) => r.data),

  /** Opening a message marks it read unless asked otherwise. */
  get: (id: number, markRead = true) =>
    client
      .get<MailMessageDetail>(`/mailbox/messages/${id}`, { params: { markRead } })
      .then((r) => r.data),

  setRead: (id: number, read: boolean) =>
    client
      .patch<MailMessage>(`/mailbox/messages/${id}/read`, null, { params: { read } })
      .then((r) => r.data),

  setReadBulk: (ids: number[], read: boolean) =>
    client
      .post<number>('/mailbox/messages/read', ids, { params: { read } })
      .then((r) => r.data),

  /**
   * Mark read every unread message one view of the mail contains — the folder on screen,
   * narrowed by whatever is in the search box. Returns how many were actually changed.
   */
  markAllRead: (scope: MailboxScope) =>
    client
      .post<number>('/mailbox/messages/read-all', null, { params: cleanParams(scope) })
      .then((r) => r.data),

  /** folderId undefined sends the message back to the Inbox. */
  move: (id: number, folderId?: number) =>
    client
      .patch<MailMessage>(`/mailbox/messages/${id}/folder`, null, {
        params: cleanParams({ folderId }),
      })
      .then((r) => r.data),

  moveBulk: (ids: number[], folderId?: number) =>
    client
      .post<number>('/mailbox/messages/folder', ids, { params: cleanParams({ folderId }) })
      .then((r) => r.data),

  link: (id: number, body: MailLinkRequest) =>
    client.put<MailMessage>(`/mailbox/messages/${id}/link`, body).then((r) => r.data),

  unlink: (id: number) =>
    client.delete<MailMessage>(`/mailbox/messages/${id}/link`).then((r) => r.data),

  /** Re-resolve every automatic link against the contacts as they are now. */
  relink: () => client.post<number>('/mailbox/relink').then((r) => r.data),

  /** The mail server's own folder tree, as the last sync listed it. Read-only. */
  serverFolders: () =>
    client.get<MailServerFolder[]>('/mailbox/server-folders').then((r) => r.data),

  status: () => client.get<MailboxStatus>('/mailbox/status').then((r) => r.data),

  /** Returns 202 immediately; watch status().syncing for the finish. */
  sync: () => client.post<MailboxStatus>('/mailbox/sync').then((r) => r.data),
};

export const mailFoldersApi = {
  list: () => client.get<MailFolder[]>('/mailbox/folders').then((r) => r.data),

  create: (body: MailFolderRequest) =>
    client.post<MailFolder>('/mailbox/folders', body).then((r) => r.data),

  update: (id: number, body: MailFolderRequest) =>
    client.put<MailFolder>(`/mailbox/folders/${id}`, body).then((r) => r.data),

  /** The folder's mail returns to the Inbox; it is not deleted with it. */
  remove: (id: number) => client.delete<void>(`/mailbox/folders/${id}`).then((r) => r.data),
};

export const mailRulesApi = {
  list: () => client.get<MailRule[]>('/mailbox/rules').then((r) => r.data),

  create: (body: MailRuleRequest) =>
    client.post<MailRule>('/mailbox/rules', body).then((r) => r.data),

  update: (id: number, body: MailRuleRequest) =>
    client.put<MailRule>(`/mailbox/rules/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/mailbox/rules/${id}`).then((r) => r.data),

  /** Run the rules over mail already synced — for after a rule is added or edited. */
  apply: () => client.post<MailRuleRun>('/mailbox/rules/apply').then((r) => r.data),
};
