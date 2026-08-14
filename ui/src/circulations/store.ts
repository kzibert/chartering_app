import { useCallback, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { circulationListsApi } from '../api/circulations';
import type {
  CirculationList,
  CirculationListEntry,
  CirculationListEntryRequest,
  ContactResponse,
} from '../api/types';

/**
 * Circulation lists live in Postgres, not in the browser. A list is a prepared document
 * that outlives a session and gets referenced by name in the send history, so keeping it
 * in localStorage would have meant a list that vanishes with a cleared cache and a history
 * entry pointing at nothing.
 *
 * Everything here is react-query, so no provider is needed: any component calling these
 * hooks shares one cache entry, and a write from the Companies tab refreshes the badge in
 * the sidebar without either knowing about the other.
 */

/** All list queries hang off this prefix so one invalidate refreshes every view of them. */
const KEY = ['circulation-lists'] as const;

export const listKeys = {
  all: KEY,
  current: [...KEY, 'current'] as const,
  saved: [...KEY, 'saved'] as const,
  one: (id: number) => [...KEY, id] as const,
};

/** Snapshot the mail-merge fields + referencing ids from a contact row. */
export function contactToEntry(ct: ContactResponse): CirculationListEntryRequest {
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

/** The saved (named) lists, for the pickers. Entry counts only — not the entries. */
export function useSavedLists() {
  return useQuery({ queryKey: listKeys.saved, queryFn: circulationListsApi.list });
}

/** One list with its entries. Skipped while `id` is undefined. */
export function useCirculationList(id: number | undefined) {
  return useQuery({
    queryKey: listKeys.one(id ?? 0),
    queryFn: () => circulationListsApi.get(id!),
    enabled: id != null,
  });
}

/**
 * The current list — the unnamed draft every tab collects into — with the operations the
 * UI performs on it. `has` answers the per-contact toggle in a contact row; it matches on
 * contact id first and falls back to the address, so a row added by hand still reads as
 * present when the same address is later found on a contact.
 */
export function useCurrentList() {
  const qc = useQueryClient();
  const query = useQuery({ queryKey: listKeys.current, queryFn: circulationListsApi.current });
  const list = query.data;
  const entries = useMemo(() => list?.entries ?? [], [list]);
  const listId = list?.id;

  // Every mutation touches the same list, so they all invalidate the whole prefix rather
  // than trying to patch the cache — these lists are small and the writes are user-paced.
  const invalidate = useCallback(() => qc.invalidateQueries({ queryKey: KEY }), [qc]);

  const byContactId = useMemo(
    () => new Set(entries.filter((e) => e.contactId != null).map((e) => e.contactId!)),
    [entries],
  );
  const byEmail = useMemo(
    () => new Set(entries.map((e) => e.email.trim().toLowerCase())),
    [entries],
  );

  const add = useMutation({
    mutationFn: (items: CirculationListEntryRequest[]) =>
      circulationListsApi.addEntries(listId!, items),
    onSuccess: invalidate,
  });

  const removeEntry = useMutation({
    mutationFn: (entryId: number) => circulationListsApi.removeEntry(listId!, entryId),
    onSuccess: invalidate,
  });

  const updateEntry = useMutation({
    mutationFn: (v: { entryId: number; body: CirculationListEntryRequest }) =>
      circulationListsApi.updateEntry(listId!, v.entryId, v.body),
    onSuccess: invalidate,
  });

  const clear = useMutation({
    mutationFn: () => circulationListsApi.clear(listId!),
    onSuccess: invalidate,
  });

  /** Copy the current list into a new named one; the draft keeps its rows. */
  const saveAs = useMutation({
    mutationFn: (v: { name: string; notes?: string }) => circulationListsApi.copy(listId!, v),
    onSuccess: invalidate,
  });

  /** Replace the current list's contents with a saved list's. */
  const load = useMutation({
    mutationFn: (sourceId: number) => circulationListsApi.load(listId!, sourceId),
    onSuccess: invalidate,
  });

  const has = useCallback(
    (ct: Pick<ContactResponse, 'id' | 'contactValue'>) =>
      byContactId.has(ct.id) || byEmail.has(ct.contactValue.trim().toLowerCase()),
    [byContactId, byEmail],
  );

  /** The entry for a contact, so a toggle can remove the row it actually added. */
  const entryFor = useCallback(
    (ct: Pick<ContactResponse, 'id' | 'contactValue'>): CirculationListEntry | undefined =>
      entries.find(
        (e) =>
          e.contactId === ct.id ||
          e.email.trim().toLowerCase() === ct.contactValue.trim().toLowerCase(),
      ),
    [entries],
  );

  return {
    list: list as CirculationList | undefined,
    listId,
    entries,
    isLoading: query.isLoading,
    has,
    entryFor,
    add,
    removeEntry,
    updateEntry,
    clear,
    saveAs,
    load,
  };
}

/** Create / rename / delete saved lists, for the manager screen. */
export function useListMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: KEY });

  const create = useMutation({
    mutationFn: (v: { name: string; notes?: string }) => circulationListsApi.create(v),
    onSuccess: invalidate,
  });
  const rename = useMutation({
    mutationFn: (v: { id: number; name: string; notes?: string }) =>
      circulationListsApi.update(v.id, { name: v.name, notes: v.notes }),
    onSuccess: invalidate,
  });
  const remove = useMutation({
    mutationFn: (id: number) => circulationListsApi.remove(id),
    onSuccess: invalidate,
  });
  /** Bulk add into any list — the target picked in the add-to-list dialog. */
  const addTo = useMutation({
    mutationFn: (v: { listId: number; entries: CirculationListEntryRequest[] }) =>
      circulationListsApi.addEntries(v.listId, v.entries),
    onSuccess: invalidate,
  });

  return { create, rename, remove, addTo };
}
