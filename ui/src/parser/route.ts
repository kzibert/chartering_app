import { useSyncExternalStore } from 'react';
import { onOwnerChange, userStorageKey } from '../auth/store';

/**
 * Which model reads mail for this person, and their own key for the cloud one.
 *
 * The key is the person's and lives here, in their browser, never on the server: it is sent as
 * two headers on Intake requests only (api/client.ts) and the api holds it for the length of
 * that request, or of the run the request started (ParserRoute on the api). That is what lets
 * the hosted instance offer Intake with nobody else's key behind it.
 *
 * localStorage rather than a cookie for the token's reason (auth/store.ts): a cookie the script
 * can read is no safer against an XSS, and one it cannot read could not be sent as a header —
 * while a cookie would ride on every request the app makes rather than the Intake ones. Under
 * `userStorageKey`, so the next person to log into a shared browser never inherits it.
 *
 * Same shape as the token store — a module variable plus `useSyncExternalStore` — because the
 * axios interceptor is a plain module and the tab bar is a component, and both read it.
 */
export type ParserProvider = 'LOCAL' | 'CLOUD';

export interface ParserRoute {
  provider: ParserProvider;
  /** Present only once the person has saved one. */
  apiKey?: string;
}

const BASE = 'chartering.parser.route.v1';
const listeners = new Set<() => void>();
const NONE: ParserRoute = { provider: 'LOCAL' };

function read(): ParserRoute {
  try {
    const raw = localStorage.getItem(userStorageKey(BASE));
    if (!raw) return NONE;
    const parsed = JSON.parse(raw) as Partial<ParserRoute>;
    const apiKey = typeof parsed.apiKey === 'string' && parsed.apiKey.trim() ? parsed.apiKey.trim() : undefined;
    return { provider: parsed.provider === 'CLOUD' ? 'CLOUD' : 'LOCAL', apiKey };
  } catch {
    return NONE;
  }
}

let route: ParserRoute = read();

function emit() {
  listeners.forEach((l) => l());
}

onOwnerChange(() => {
  route = read();
  emit();
});

export function getParserRoute(): ParserRoute {
  return route;
}

/** Whether Intake requests from this browser read with the cloud: chosen, and a key held. */
export function readsWithCloud(r: ParserRoute = route): boolean {
  return r.provider === 'CLOUD' && !!r.apiKey;
}

export function setParserRoute(next: ParserRoute) {
  const clean: ParserRoute = {
    provider: next.provider,
    apiKey: next.apiKey?.trim() || undefined,
  };
  try {
    localStorage.setItem(userStorageKey(BASE), JSON.stringify(clean));
  } catch {
    /* storage unavailable: the choice still lives in memory for this visit */
  }
  route = clean;
  emit();
}

/** Forget the key. The provider choice goes back to the local model. */
export function forgetParserKey() {
  try {
    localStorage.removeItem(userStorageKey(BASE));
  } catch {
    /* nothing stored to remove */
  }
  route = NONE;
  emit();
}

/** The headers an Intake request carries: none unless this browser reads with the cloud. */
export function parserHeaders(): Record<string, string> {
  return readsWithCloud()
    ? { 'X-Parser-Provider': 'CLOUD', 'X-Parser-Key': route.apiKey as string }
    : {};
}

export function useParserRoute(): ParserRoute {
  return useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange);
      return () => listeners.delete(onChange);
    },
    () => route,
  );
}

/** For a key shown on a screen: the last four characters, never the key. */
export function keyHint(apiKey?: string): string | undefined {
  if (!apiKey) return undefined;
  return apiKey.length <= 8 ? '…' : `…${apiKey.slice(-4)}`;
}
