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

function emit() {
  listeners.forEach((l) => l());
}

export function getToken(): string | null {
  return token;
}

export function setToken(next: string | null) {
  if (token === next) return;
  token = next;
  try {
    if (next) localStorage.setItem(KEY, next);
    else localStorage.removeItem(KEY);
  } catch {
    /* storage unavailable — the value still lives in memory for this visit */
  }
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
          token = read();
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
