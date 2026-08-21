import { createContext, useContext, useMemo, useState, type ReactNode } from 'react';

/**
 * Which contact row currently has its controls open, shared across one list.
 *
 * In edit mode a row carries nine write controls — main, circ, no circ, not working, the
 * WhatsApp check, add-to-list, edit, ban, delete — and rendering all nine on every row
 * turned a list of six contacts into fifty-odd buttons wrapping over two lines each. They
 * now live behind a click on the row, and only one row's worth is ever on screen.
 *
 * The state sits here rather than inside the row so that opening one closes the last. Kept
 * per list, not globally: two lists on screen at once (the People tab's groups) are one
 * list conceptually, but the vessel drawer's owner contacts are not, and neither should be
 * able to collapse the other's open row from a distance.
 */
interface Expansion {
  openId: number | null;
  toggle: (id: number) => void;
}

const ExpansionContext = createContext<Expansion | null>(null);

/** Wrap a list of {@link ContactLine}s to give them one-open-at-a-time behaviour. */
export function ContactRowExpansion({ children }: { children: ReactNode }) {
  const [openId, setOpenId] = useState<number | null>(null);
  const value = useMemo<Expansion>(
    () => ({ openId, toggle: (id) => setOpenId((current) => (current === id ? null : id)) }),
    [openId],
  );
  return <ExpansionContext.Provider value={value}>{children}</ExpansionContext.Provider>;
}

/**
 * Whether this row is open, and how to flip it.
 *
 * Falls back to per-row state when no provider is above it, so a {@link ContactLine} dropped
 * somewhere new still works — it just loses the one-at-a-time part rather than throwing.
 * Both hooks run on every render; the provider only decides which answer is returned.
 */
export function useContactRowExpansion(id: number): readonly [boolean, () => void] {
  const shared = useContext(ExpansionContext);
  const [local, setLocal] = useState(false);
  return shared
    ? ([shared.openId === id, () => shared.toggle(id)] as const)
    : ([local, () => setLocal((v) => !v)] as const);
}
