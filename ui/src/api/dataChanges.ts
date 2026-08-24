import { client, cleanParams } from './client';
import type { PageResponse } from './types';

/**
 * The change log: who changed what, when, and what it was before.
 *
 * Two row shapes share the table, told apart by `fieldName`. One field of an update carries
 * a name and the before/after values; a whole record appearing or vanishing carries no name,
 * and the values are JSON snapshots of the row. See `ChangeSummary` for how each is drawn.
 */
export interface DataChangeResponse {
  id: number;
  /** Shared by every entry one save or one import wrote. */
  changeSet: string;
  entityType: string;
  entityId: number;
  /**
   * What the record was called at the time. The only name a deleted record has left — there
   * is nothing to look it up in any more.
   */
  entityLabel?: string;
  operation: 'create' | 'update' | 'delete';
  /** Absent when the entry is a whole-record create or delete. */
  fieldName?: string;
  oldValue?: string;
  newValue?: string;
  changedAt: string;
  changedBy?: string;
  /** Why, when something said — "Contact import". */
  context?: string;
  /** Whether this entry can be put back with one click. */
  revertible: boolean;
  /** Why not, when it cannot. */
  revertBlockedReason?: string;
}

export interface DataChangeFilter {
  entityType?: string;
  entityId?: number;
  operation?: string;
  field?: string;
  changedBy?: string;
  changeSet?: string;
  /** ISO date-times. */
  from?: string;
  until?: string;
  /** Matches the label and both values, so a deleted record is findable by its contents. */
  text?: string;
  page?: number;
  size?: number;
  sort?: string;
}

export const dataChangesApi = {
  search: (filter: DataChangeFilter) =>
    client
      .get<PageResponse<DataChangeResponse>>('/data-changes', { params: cleanParams(filter) })
      .then((r) => r.data),

  entityTypes: () =>
    client.get<string[]>('/data-changes/entity-types').then((r) => r.data),

  users: () => client.get<string[]>('/data-changes/users').then((r) => r.data),

  /** Put one field back. Refused if the field has changed again since — see the API note. */
  revert: (id: number) =>
    client.post<DataChangeResponse>(`/data-changes/${id}/revert`).then((r) => r.data),
};
