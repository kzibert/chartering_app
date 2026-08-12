import { useSyncExternalStore } from 'react';

/**
 * "Recently opened" trail for the dashboard. Client-side only, mirrored to
 * localStorage so it survives reloads — nothing is sent to the API.
 *
 * A module-level store rather than a context provider: the recording happens deep
 * inside the vessel/company drawers, which are rendered from half a dozen places
 * (including inside each other), and this way none of them need a provider above.
 */
const STORAGE_KEY = 'chartering.recent.v1';
/** Per kind, not overall — one busy afternoon of vessels shouldn't evict every company. */
const LIMIT = 8;

export type RecentKind = 'vessel' | 'company' | 'person';

export interface RecentEntry {
  kind: RecentKind;
  id: number;
  title: string;
  subtitle?: string;
  /** People are opened through their company's drawer, so the trail remembers the way back. */
  companyId?: number;
  /** epoch ms of the last open */
  at: number;
}

function load(): RecentEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : [];
    return Array.isArray(parsed) ? (parsed as RecentEntry[]) : [];
  } catch {
    return [];
  }
}

let entries: RecentEntry[] = load();
const listeners = new Set<() => void>();

function commit(next: RecentEntry[]) {
  entries = next;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(entries));
  } catch {
    /* storage full or disabled — the trail still works for this visit */
  }
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Move an item to the front of its kind. Safe to call from a render effect: reopening
 * whatever is already on top is a no-op, so it can't loop.
 */
export function recordRecent(entry: Omit<RecentEntry, 'at'>) {
  const top = entries.find((e) => e.kind === entry.kind);
  if (
    top &&
    top.id === entry.id &&
    top.title === entry.title &&
    top.subtitle === entry.subtitle
  ) {
    return;
  }

  const withoutDupe = entries.filter((e) => !(e.kind === entry.kind && e.id === entry.id));
  const perKind = new Map<RecentKind, number>();
  commit(
    [{ ...entry, at: Date.now() }, ...withoutDupe].filter((e) => {
      const n = (perKind.get(e.kind) ?? 0) + 1;
      perKind.set(e.kind, n);
      return n <= LIMIT;
    }),
  );
}

/** Drop one kind's trail, or everything when called with no argument. */
export function clearRecent(kind?: RecentKind) {
  commit(kind ? entries.filter((e) => e.kind !== kind) : []);
}

/** Forget a single item — used when opening it turns up nothing (it was deleted). */
export function forgetRecent(kind: RecentKind, id: number) {
  commit(entries.filter((e) => !(e.kind === kind && e.id === id)));
}

const snapshot = () => entries;

/** Most-recent-first trail for one kind. */
export function useRecent(kind: RecentKind): RecentEntry[] {
  return useSyncExternalStore(subscribe, snapshot).filter((e) => e.kind === kind);
}
