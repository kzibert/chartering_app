import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { mailFoldersApi, mailRulesApi, mailboxApi } from '../api/mailbox';
import type {
  MailFolderRequest,
  MailLinkRequest,
  MailRuleRequest,
  MailboxFilter,
} from '../api/types';

/**
 * Mailbox queries, in one place for the same reason the circulation lists have one: the
 * folder rail, the message list and the reading pane are three components that must never
 * disagree about what is where, and they only stay in step if a write from any of them
 * invalidates all of it.
 *
 * Everything hangs off one key prefix, so `invalidate()` after a move refreshes the counts
 * in the rail as well as the rows in the table — the badge going down is half of what tells
 * the user the move worked.
 */
const KEY = ['mailbox'] as const;

export const mailboxKeys = {
  all: KEY,
  messages: (filter: MailboxFilter) => [...KEY, 'messages', filter] as const,
  message: (id: number) => [...KEY, 'message', id] as const,
  folders: [...KEY, 'folders'] as const,
  rules: [...KEY, 'rules'] as const,
  status: [...KEY, 'status'] as const,
};

/** Refresh everything mailbox-shaped. Cheap: these are small, user-paced queries. */
export function useMailboxInvalidator() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: KEY });
}

export const useMailMessages = (filter: MailboxFilter) =>
  useQuery({
    queryKey: mailboxKeys.messages(filter),
    queryFn: () => mailboxApi.search(filter),
    // Paging and filtering both replace the whole result set; keeping the previous page on
    // screen while the next loads is what stops the table flickering to empty on every
    // keystroke of the search box.
    placeholderData: (prev) => prev,
  });

/** One message with its body. Marking it read is the server's default on open. */
export const useMailMessage = (id?: number) =>
  useQuery({
    queryKey: mailboxKeys.message(id ?? 0),
    queryFn: () => mailboxApi.get(id!),
    enabled: id != null,
  });

export const useMailFolders = () =>
  useQuery({ queryKey: mailboxKeys.folders, queryFn: mailFoldersApi.list });

export const useMailRules = () =>
  useQuery({ queryKey: mailboxKeys.rules, queryFn: mailRulesApi.list });

/**
 * Sync status. Polled while a sync is in flight and left alone otherwise: the fetch itself
 * runs on the server's own thread, so the only way the tab can know it has finished is to
 * ask — but asking every two seconds forever, for a mailbox that syncs every five minutes,
 * would be pure noise.
 */
export const useMailboxStatus = () =>
  useQuery({
    queryKey: mailboxKeys.status,
    queryFn: mailboxApi.status,
    refetchInterval: (query) => (query.state.data?.syncing ? 2000 : false),
  });

/** Everything that writes to a message. All of them invalidate the whole prefix. */
export function useMailMessageMutations() {
  const invalidate = useMailboxInvalidator();

  const setRead = useMutation({
    mutationFn: (v: { id: number; read: boolean }) => mailboxApi.setRead(v.id, v.read),
    onSuccess: invalidate,
  });
  const setReadBulk = useMutation({
    mutationFn: (v: { ids: number[]; read: boolean }) => mailboxApi.setReadBulk(v.ids, v.read),
    onSuccess: invalidate,
  });
  const move = useMutation({
    mutationFn: (v: { id: number; folderId?: number }) => mailboxApi.move(v.id, v.folderId),
    onSuccess: invalidate,
  });
  const moveBulk = useMutation({
    mutationFn: (v: { ids: number[]; folderId?: number }) =>
      mailboxApi.moveBulk(v.ids, v.folderId),
    onSuccess: invalidate,
  });
  const link = useMutation({
    mutationFn: (v: { id: number; body: MailLinkRequest }) => mailboxApi.link(v.id, v.body),
    // 'companies' and 'contacts' too: linking with createContact adds a contact row, which
    // the company drawer and the circulation collectors read from their own keys.
    onSuccess: invalidate,
  });
  const unlink = useMutation({
    mutationFn: (id: number) => mailboxApi.unlink(id),
    onSuccess: invalidate,
  });
  const relink = useMutation({ mutationFn: mailboxApi.relink, onSuccess: invalidate });

  return { setRead, setReadBulk, move, moveBulk, link, unlink, relink };
}

export function useMailFolderMutations() {
  const invalidate = useMailboxInvalidator();
  const create = useMutation({
    mutationFn: (body: MailFolderRequest) => mailFoldersApi.create(body),
    onSuccess: invalidate,
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: MailFolderRequest }) =>
      mailFoldersApi.update(v.id, v.body),
    onSuccess: invalidate,
  });
  const remove = useMutation({
    mutationFn: (id: number) => mailFoldersApi.remove(id),
    onSuccess: invalidate,
  });
  return { create, update, remove };
}

export function useMailRuleMutations() {
  const invalidate = useMailboxInvalidator();
  const create = useMutation({
    mutationFn: (body: MailRuleRequest) => mailRulesApi.create(body),
    onSuccess: invalidate,
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: MailRuleRequest }) => mailRulesApi.update(v.id, v.body),
    onSuccess: invalidate,
  });
  const remove = useMutation({
    mutationFn: (id: number) => mailRulesApi.remove(id),
    onSuccess: invalidate,
  });
  /** Re-file the mail already synced. The one action that moves messages in bulk. */
  const apply = useMutation({ mutationFn: mailRulesApi.apply, onSuccess: invalidate });
  return { create, update, remove, apply };
}

/** Kick off a fetch. The status query starts polling on its own once `syncing` is true. */
export function useMailboxSync() {
  const invalidate = useMailboxInvalidator();
  return useMutation({ mutationFn: mailboxApi.sync, onSuccess: invalidate });
}
