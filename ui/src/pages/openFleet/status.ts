import type { PositionStatus, VesselResponse } from '../../api/types';

export const POSITION_STATUS_META: Record<PositionStatus, { label: string; color: string; hint: string }> = {
  LIVE: { label: 'Live', color: 'green', hint: 'Current, and what Match reads' },
  FIXED: { label: 'Fixed', color: 'blue', hint: 'She fixed — history the moment it is set' },
  WITHDRAWN: { label: 'Withdrawn', color: 'default', hint: 'Pulled by whoever reported it' },
  SUPERSEDED: {
    label: 'Superseded',
    color: 'default',
    hint: 'A newer report from the same source replaced it. Kept, not deleted — "she was said to be open Adriatic and then wasn\'t" is worth being able to look back at.',
  },
  EXPIRED: { label: 'Expired', color: 'default', hint: 'The open dates passed with nothing said since' },
};

export const POSITION_STATUSES = Object.keys(POSITION_STATUS_META) as PositionStatus[];

export const POSITION_STATUS_OPTIONS = POSITION_STATUSES.map((value) => ({
  value,
  label: POSITION_STATUS_META[value].label,
}));

/**
 * How a fleet list quotes a hull: the bigger of her two deadweight figures, in tonnes.
 *
 * Position lists say "cc" — cargo capacity — and the vessel records hold whichever figure
 * somebody had, with 0 standing for unknown. Preferring DWCC and falling back to DWT is what
 * the API's size filter does, and this exists so the number on screen is the one that was
 * filtered on rather than a different one.
 */
export function fleetSize(v: VesselResponse): { value?: number; label: string } {
  if (v.deadweightCargoCapacity) return { value: v.deadweightCargoCapacity, label: 'dwcc' };
  if (v.deadweightTonnage) return { value: v.deadweightTonnage, label: 'dwt' };
  return { label: '—' };
}

/** "6,100 dwcc", or a dash when neither figure is on file. */
export function formatFleetSize(v: VesselResponse): string {
  const { value, label } = fleetSize(v);
  return value == null ? '—' : `${value.toLocaleString()} ${label}`;
}

/**
 * How stale a reading is, said the way a broker would.
 *
 * The thresholds are not decoration: a position list is a weekly document, so anything
 * inside three days is simply current, a week old is worth a second look, and past a
 * fortnight it should be read as an archive rather than as a fleet.
 */
export function staleness(ageDays: number): { text: string; color?: string } {
  if (ageDays <= 0) return { text: 'today' };
  if (ageDays === 1) return { text: 'yesterday' };
  if (ageDays <= 3) return { text: `${ageDays} days ago` };
  if (ageDays <= 14) return { text: `${ageDays} days ago`, color: 'orange' };
  return { text: `${ageDays} days ago`, color: 'red' };
}

/** "1–3 Sep", "from 1 Sep", or the words the list used when it gave no dates. */
export function formatOpenDates(from?: string, to?: string, text?: string): string {
  const d = (iso: string) =>
    new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
  if (from && to) return `${d(from)} – ${d(to)}`;
  if (from) return `from ${d(from)}`;
  if (to) return `until ${d(to)}`;
  return text ?? '—';
}
