import { client, cleanParams } from './client';
import type {
  CirculationList,
  CirculationListEntryRequest,
  CirculationListRequest,
  CirculationMessage,
  CirculationRun,
  CirculationRunDetail,
  CompanyFilter,
  ContactResponse,
  PageParams,
  PageResponse,
  PeopleFilter,
  VesselFilter,
} from './types';

/** Saved recipient lists, plus the unnamed draft the tabs collect into. */
export const circulationListsApi = {
  /** Saved lists with entry counts; the draft is fetched separately. */
  list: () => client.get<CirculationList[]>('/circulation-lists').then((r) => r.data),

  current: () =>
    client.get<CirculationList>('/circulation-lists/current').then((r) => r.data),

  get: (id: number) =>
    client.get<CirculationList>(`/circulation-lists/${id}`).then((r) => r.data),

  create: (body: CirculationListRequest) =>
    client.post<CirculationList>('/circulation-lists', body).then((r) => r.data),

  update: (id: number, body: CirculationListRequest) =>
    client.put<CirculationList>(`/circulation-lists/${id}`, body).then((r) => r.data),

  /** "Save as": copies the entries into a new named list, leaving the source intact. */
  copy: (id: number, body: CirculationListRequest) =>
    client.post<CirculationList>(`/circulation-lists/${id}/copy`, body).then((r) => r.data),

  /** Replaces `id`'s entries with `sourceId`'s — loading a saved list into the current one. */
  load: (id: number, sourceId: number) =>
    client.post<CirculationList>(`/circulation-lists/${id}/load/${sourceId}`).then((r) => r.data),

  remove: (id: number) =>
    client.delete<void>(`/circulation-lists/${id}`).then((r) => r.data),

  /** Returns {added, skipped} — addresses already on the list are skipped, not duplicated. */
  addEntries: (id: number, entries: CirculationListEntryRequest[]) =>
    client
      .post<{ added: number; skipped: number }>(`/circulation-lists/${id}/entries`, entries)
      .then((r) => r.data),

  updateEntry: (id: number, entryId: number, body: CirculationListEntryRequest) =>
    client
      .put<CirculationList>(`/circulation-lists/${id}/entries/${entryId}`, body)
      .then((r) => r.data),

  removeEntry: (id: number, entryId: number) =>
    client.delete<void>(`/circulation-lists/${id}/entries/${entryId}`).then((r) => r.data),

  /**
   * Subtract addresses from a list. Matched by address, not entry id, so it works across
   * lists — "take everyone on this saved list off the current one".
   */
  removeEntriesByEmail: (id: number, emails: string[]) =>
    client
      .post<{ removed: number; notOnList: number }>(
        `/circulation-lists/${id}/entries/remove`,
        emails,
      )
      .then((r) => r.data),

  clear: (id: number) =>
    client.delete<CirculationList>(`/circulation-lists/${id}/entries`).then((r) => r.data),
};

/**
 * Bulk collection from the entity tabs. Each call returns the contacts the circ/main flags
 * select — circ-flagged addresses when the person has any, else their main, else all their
 * working ones. Passing explicit ids overrides the filter, which is how the row checkboxes
 * differ from "add all matching".
 */
export const collectApi = {
  fromCompanies: (filter: Partial<CompanyFilter>, companyIds: number[], confirmedOnly: boolean) =>
    client
      .get<ContactResponse[]>('/companies/contacts', {
        params: cleanParams({ ...stripPaging(filter), companyId: companyIds, confirmedOnly }),
      })
      .then((r) => r.data),

  fromPeople: (filter: Partial<PeopleFilter>, personIds: number[], confirmedOnly: boolean) =>
    client
      .get<ContactResponse[]>('/people/contacts', {
        params: cleanParams({ ...stripPaging(filter), personId: personIds, confirmedOnly }),
      })
      .then((r) => r.data),

  /** Vessels collect their owner companies' addresses, not the vessels' own. */
  fromVessels: (filter: Partial<VesselFilter>, vesselIds: number[], confirmedOnly: boolean) =>
    client
      .get<ContactResponse[]>('/vessels/contacts', {
        params: cleanParams({ ...stripPaging(filter), vesselId: vesselIds, confirmedOnly }),
      })
      .then((r) => r.data),
};

/** Past circulations: who was reached, and what each of them received. */
export const circulationsApi = {
  history: (params: PageParams = {}) =>
    client
      .get<PageResponse<CirculationRun>>('/circulations', { params: cleanParams(params) })
      .then((r) => r.data),

  get: (id: number) =>
    client.get<CirculationRunDetail>(`/circulations/${id}`).then((r) => r.data),

  /** The message one recipient actually got, re-rendered from what the run stored. */
  message: (id: number, recipientId: number) =>
    client
      .get<CirculationMessage>(`/circulations/${id}/recipients/${recipientId}/message`)
      .then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/circulations/${id}`).then((r) => r.data),
};

/**
 * These endpoints operate on the whole filtered set, so page/size/sort are meaningless —
 * and `sort` in particular would 400 against a non-Pageable endpoint.
 */
function stripPaging<T extends PageParams>(filter: T): Omit<T, 'page' | 'size' | 'sort'> {
  const { page: _page, size: _size, sort: _sort, ...rest } = filter;
  return rest;
}
