import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react';

/**
 * Mail being written, kept above the pages so it survives moving around the app.
 *
 * <p><b>Why a draft is not the composer's own state any more.</b> The reply window lived inside
 * the message drawer and Reach out inside the company drawer, so the text lived and died with
 * them: looking up a vessel's DWT halfway through an answer meant closing the answer. Here a
 * draft is a row in app-level state, the window is only its view, and minimising is hiding the
 * view — the text, the picked addresses and the footer stay until it is sent or discarded.
 *
 * <p>Nothing is written to the server or to browser storage. A draft is what somebody is
 * writing right now; one that outlives a reload would be a second drafts folder beside the
 * mailbox's own, and the one nobody checks.
 */

interface DraftBase {
  id: string;
  /** Shown on the minimised chip. */
  label: string;
  minimized: boolean;
  to: string;
  cc: string[];
  subject: string;
  body: string;
  /** undefined = not chosen yet (the reply default applies); null = no footer, chosen. */
  footerId: number | null | undefined;
}

export interface ReplyDraft extends DraftBase {
  kind: 'reply';
  messageId: number;
  includeOriginal: boolean;
}

export interface ReachOutDraft extends DraftBase {
  kind: 'reach-out';
  companyId: number;
  companyName: string;
  step: 'pick' | 'write';
  /** Contact ids in the order picked: the first is To, the rest are copied. */
  picked: number[];
}

export type Draft = ReplyDraft | ReachOutDraft;

interface ComposerApi {
  drafts: Draft[];
  /** Answer a message; an unsent answer to the same message is brought back instead. */
  openReply: (m: { id: number; fromAddress: string; subject?: string }) => void;
  /** Write to a firm; an unsent message to the same firm is brought back instead. */
  openReachOut: (c: { id: number; name: string }) => void;
  update: (id: string, patch: Partial<Draft>) => void;
  minimize: (id: string) => void;
  restore: (id: string) => void;
  discard: (id: string) => void;
}

const Ctx = createContext<ComposerApi | null>(null);

/** "Re: x" once, however many times a thread has been round. */
export function replySubject(subject?: string): string {
  const s = (subject ?? '').trim();
  if (!s) return 'Re:';
  return /^re\s*:/i.test(s) ? s : `Re: ${s}`;
}

let seq = 0;
const newId = () => `draft-${Date.now()}-${++seq}`;

export function ComposerProvider({ children }: { children: ReactNode }) {
  const [drafts, setDrafts] = useState<Draft[]>([]);

  // Bringing one forward sends the others to the dock: one window at a time, the rest waiting.
  const focus = useCallback(
    (id: string) => setDrafts((all) => all.map((d) => ({ ...d, minimized: d.id !== id }))),
    [],
  );

  const openReply = useCallback<ComposerApi['openReply']>((m) => {
    setDrafts((all) => {
      const existing = all.find((d) => d.kind === 'reply' && d.messageId === m.id);
      if (existing) return all.map((d) => ({ ...d, minimized: d.id !== existing.id }));
      const subject = replySubject(m.subject);
      const draft: ReplyDraft = {
        id: newId(), kind: 'reply', label: subject, minimized: false,
        messageId: m.id, to: m.fromAddress, cc: [], subject, body: '',
        footerId: undefined, includeOriginal: true,
      };
      return [...all.map((d) => ({ ...d, minimized: true })), draft];
    });
  }, []);

  const openReachOut = useCallback<ComposerApi['openReachOut']>((c) => {
    setDrafts((all) => {
      const existing = all.find((d) => d.kind === 'reach-out' && d.companyId === c.id);
      if (existing) return all.map((d) => ({ ...d, minimized: d.id !== existing.id }));
      const draft: ReachOutDraft = {
        id: newId(), kind: 'reach-out', label: `To ${c.name}`, minimized: false,
        companyId: c.id, companyName: c.name, step: 'pick', picked: [],
        to: '', cc: [], subject: '', body: '', footerId: undefined,
      };
      return [...all.map((d) => ({ ...d, minimized: true })), draft];
    });
  }, []);

  const update = useCallback<ComposerApi['update']>(
    (id, patch) =>
      setDrafts((all) => all.map((d) => (d.id === id ? ({ ...d, ...patch } as Draft) : d))),
    [],
  );
  const minimize = useCallback(
    (id: string) => setDrafts((all) => all.map((d) => (d.id === id ? { ...d, minimized: true } : d))),
    [],
  );
  const discard = useCallback((id: string) => setDrafts((all) => all.filter((d) => d.id !== id)), []);

  const api = useMemo<ComposerApi>(
    () => ({ drafts, openReply, openReachOut, update, minimize, restore: focus, discard }),
    [drafts, openReply, openReachOut, update, minimize, focus, discard],
  );
  return <Ctx.Provider value={api}>{children}</Ctx.Provider>;
}

export function useComposer(): ComposerApi {
  const api = useContext(Ctx);
  if (!api) throw new Error('useComposer outside ComposerProvider');
  return api;
}
