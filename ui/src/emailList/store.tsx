import {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
  useMemo,
  type ReactNode,
} from 'react';
import type { ContactResponse, EmailListEntry } from '../api/types';

// Client-side only: the list lives in React state, mirrored to localStorage so it
// survives reloads/navigation. No backend/DB involvement (by design, for now).
const STORAGE_KEY = 'chartering.emailList.v1';

function loadInitial(): EmailListEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/** Snapshot the mail-merge fields + referencing ids from a contact row. */
export function contactToEntry(ct: ContactResponse): EmailListEntry {
  return {
    contactId: ct.id,
    email: ct.contactValue,
    personId: ct.personId,
    personName: ct.personName,
    greetingName: ct.greetingName,
    title: ct.title,
    companyId: ct.companyId,
    companyName: ct.companyName,
  };
}

interface EmailListContextValue {
  entries: EmailListEntry[];
  has: (contactId: number) => boolean;
  add: (entry: EmailListEntry) => void;
  remove: (contactId: number) => void;
  update: (contactId: number, patch: Partial<EmailListEntry>) => void;
  clear: () => void;
}

const EmailListContext = createContext<EmailListContextValue | null>(null);

export function EmailListProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<EmailListEntry[]>(loadInitial);

  useEffect(() => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
    } catch {
      /* storage full or disabled — list still works in memory */
    }
  }, [entries]);

  const has = useCallback(
    (contactId: number) => entries.some((e) => e.contactId === contactId),
    [entries],
  );

  // Dedupe by contactId so the same email can't be added twice.
  const add = useCallback((entry: EmailListEntry) => {
    setEntries((prev) =>
      prev.some((e) => e.contactId === entry.contactId) ? prev : [...prev, entry],
    );
  }, []);

  const remove = useCallback((contactId: number) => {
    setEntries((prev) => prev.filter((e) => e.contactId !== contactId));
  }, []);

  const update = useCallback((contactId: number, patch: Partial<EmailListEntry>) => {
    setEntries((prev) =>
      prev.map((e) => (e.contactId === contactId ? { ...e, ...patch } : e)),
    );
  }, []);

  const clear = useCallback(() => setEntries([]), []);

  const value = useMemo(
    () => ({ entries, has, add, remove, update, clear }),
    [entries, has, add, remove, update, clear],
  );

  return <EmailListContext.Provider value={value}>{children}</EmailListContext.Provider>;
}

export function useEmailList(): EmailListContextValue {
  const ctx = useContext(EmailListContext);
  if (!ctx) throw new Error('useEmailList must be used within an EmailListProvider');
  return ctx;
}
