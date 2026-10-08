import { useEffect, useState } from 'react';
import type { FormInstance } from 'antd';
import { userStorageKey } from '../auth/store';

// Every page unmounts when you switch tabs (react-router swaps the route element),
// so plain useState filters are lost on navigation. Mirroring them to localStorage
// keeps a search alive until the page's Reset button explicitly clears it.
// Per person (userStorageKey): a search somebody left set is theirs, not the next login's.
const PREFIX = 'chartering.filters.v1.';
const storageKey = (key: string) => userStorageKey(PREFIX + key);

function load<T>(key: string | undefined, fallback: T): T {
  if (!key) return fallback;
  try {
    const raw = localStorage.getItem(storageKey(key));
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

/** useState mirrored to localStorage under `key`. Without a key it is plain useState. */
export function usePersistedState<T>(key: string | undefined, initial: T) {
  const [value, setValue] = useState<T>(() => load(key, initial));

  useEffect(() => {
    if (!key) return;
    try {
      const raw = JSON.stringify(value);
      // A cleared filter serialises to undefined; drop the entry instead of storing "undefined".
      if (raw === undefined) localStorage.removeItem(storageKey(key));
      else localStorage.setItem(storageKey(key), raw);
    } catch {
      /* storage full or disabled — filters still work for this visit */
    }
  }, [key, value]);

  return [value, setValue] as const;
}

/**
 * Persisted filter object for a search page, kept in sync with its antd form.
 *
 * The stored value drives the query immediately on mount; the form inputs are
 * repopulated once so the visible fields match the results being shown. The form's
 * own initialValues are deliberately left alone, so `form.resetFields()` in a Reset
 * button still falls back to the page defaults rather than the restored search.
 */
export function usePersistedFilters<T extends object>(key: string, form: FormInstance) {
  const [filters, setFilters] = usePersistedState<T>(key, {} as T);

  useEffect(() => {
    form.setFieldsValue(filters);
    // Mount only: later edits come from the form itself, and re-syncing on every
    // change would fight the user's typing.
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return [filters, setFilters] as const;
}
