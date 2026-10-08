import { useSyncExternalStore } from 'react';

/**
 * The token, and who is watching it.
 *
 * Not react-query, and not a context provider, for two reasons. The axios interceptor in
 * api/client.ts has to read the token on every request and clear it on a 401 — and it is a
 * plain module, not a component, so it cannot use a hook. And the login screen has to
 * appear the moment the token goes, from wherever it went, which means one value that both
 * a module and the component tree can read and subscribe to. `useSyncExternalStore` over a
 * module-level variable is exactly that, in about thirty lines.
 *
 * localStorage rather than a cookie: the token travels in an Authorization header, which is
 * what makes the API safe to leave without CSRF protection (a cross-site form cannot set a
 * header). The trade is that a successful XSS could read it — but an XSS on this app could
 * equally just make the calls itself with the session it is running inside, so the cookie
 * would buy less than it looks like it would.
 */
const KEY = 'chartering.auth.token';

function read(): string | null {
  try {
    return localStorage.getItem(KEY);
  } catch {
    // Private mode with storage disabled: the app still works, the login just does not
    // survive a reload.
    return null;
  }
}

let token: string | null = read();
const listeners = new Set<() => void>();

/**
 * Who is logged in, as far as this browser's own storage is concerned: the token's subject,
 * which is the user id (JwtService). Read off the token rather than asked of the server
 * because the stores that need it are plain modules loaded before any request is made.
 *
 * Not a credential check — the server still decides everything. A token that does not
 * decode simply has no owner here, and the login it carries is refused soon enough.
 */
function ownerOf(t: string | null): string | null {
  if (!t) return null;
  try {
    const payload = t.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const sub = JSON.parse(atob(payload))?.sub;
    return typeof sub === 'string' || typeof sub === 'number' ? String(sub) : null;
  } catch {
    return null;
  }
}

let owner: string | null = ownerOf(token);

/**
 * A browser-storage key belonging to the person logged in: `chartering.u42.recent.v1` for
 * `chartering.recent.v1`.
 *
 * Everything this app keeps in localStorage — a half-written circular, the dashboard's
 * recently opened trail, a search left set — is somebody's, and a browser is shared by
 * whoever logs into it. Under one key for all of them, the next person to log in, on any
 * desk, was handed the last person's draft and the names of the companies they had opened.
 */
export function userStorageKey(base: string): string {
  return base.replace(/^chartering\./, `chartering.u${owner ?? 'none'}.`);
}

/**
 * The keys written before they were per person belonged to whoever happened to be logged
 * in, and nothing can say who that was, so they are dropped rather than handed to the next
 * login — which is exactly the leak this exists to close. Runs once, on load.
 */
function dropUnownedStorage() {
  try {
    const stale: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k && k.startsWith('chartering.') && k !== KEY && !/^chartering\.u[^.]+\./.test(k)) {
        stale.push(k);
      }
    }
    stale.forEach((k) => localStorage.removeItem(k));
  } catch {
    /* storage unavailable — there is nothing stored to leak either */
  }
}
dropUnownedStorage();

const ownerListeners = new Set<() => void>();

/**
 * Called whenever the person logged in changes — a login, a logout, a session expiring, or
 * another tab doing any of those. For the stores that hold one person's state in memory and
 * have to drop it before the next person's screen renders.
 */
export function onOwnerChange(listener: () => void): () => void {
  ownerListeners.add(listener);
  return () => ownerListeners.delete(listener);
}

/** Before `emit`, so nothing re-renders for the new login while still holding the old one's state. */
function adopt(next: string | null) {
  token = next;
  const nextOwner = ownerOf(next);
  if (nextOwner !== owner) {
    owner = nextOwner;
    ownerListeners.forEach((l) => l());
  }
}

function emit() {
  listeners.forEach((l) => l());
}

export function getToken(): string | null {
  return token;
}

export function setToken(next: string | null) {
  if (token === next) return;
  try {
    if (next) localStorage.setItem(KEY, next);
    else localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable — the value still lives in memory for this visit */
  }
  adopt(next);
  emit();
}

export function clearToken() {
  setToken(null);
}

/**
 * Re-render on login and logout. Also fires when another tab logs out: the `storage` event
 * only reaches other tabs, which is precisely the case a single module variable would miss.
 */
export function useToken(): string | null {
  return useSyncExternalStore(
    (onChange) => {
      listeners.add(onChange);
      const onStorage = (e: StorageEvent) => {
        if (e.key === KEY) {
          adopt(read());
          emit();
        }
      };
      window.addEventListener('storage', onStorage);
      return () => {
        listeners.delete(onChange);
        window.removeEventListener('storage', onStorage);
      };
    },
    () => token,
  );
}

export const useIsAuthenticated = () => useToken() !== null;
