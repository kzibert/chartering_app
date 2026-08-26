import type { CargoStatus } from '../../api/types';

/**
 * How each cargo status is shown, in one place.
 *
 * The colours are doing work rather than decorating: on a list of forty cargoes the only
 * question being asked at a glance is which are still live, so the three live states share
 * the warm end of the palette and the four closed ones are grey or red. A per-status colour
 * chosen for prettiness would lose that.
 */
export const CARGO_STATUS_META: Record<CargoStatus, { label: string; color: string; hint: string }> = {
  OPEN: { label: 'Open', color: 'gold', hint: 'Received, nothing offered yet' },
  QUOTED: { label: 'Quoted', color: 'orange', hint: 'Tonnage put forward, waiting on the charterer' },
  FIRM: { label: 'Firm', color: 'volcano', hint: 'On subs or in firm negotiation' },
  FIXED: { label: 'Fixed', color: 'green', hint: 'Done — kept, because a fixture is market history' },
  FAILED: { label: 'Failed', color: 'red', hint: 'Negotiated and went nowhere' },
  EXPIRED: { label: 'Expired', color: 'default', hint: 'The laycan passed with nothing done' },
  WITHDRAWN: { label: 'Withdrawn', color: 'default', hint: 'The charterer pulled it' },
};

export const CARGO_STATUSES = Object.keys(CARGO_STATUS_META) as CargoStatus[];

export const CARGO_STATUS_OPTIONS = CARGO_STATUSES.map((value) => ({
  value,
  label: CARGO_STATUS_META[value].label,
}));

/** "25,000 MT +/- 10%", or as much of it as the enquiry gave. */
export function formatQuantity(
  quantity?: number,
  unit?: string,
  tolerance?: string,
): string {
  if (quantity == null) return tolerance ?? '—';
  const base = `${quantity.toLocaleString()} ${unit ?? 'MT'}`;
  return tolerance ? `${base} ${tolerance}` : base;
}

/**
 * A place as one line: the port if there is one, else whatever was written, with the trade
 * area in brackets when it adds something the port name did not already say.
 */
export function formatPlace(
  portName?: string,
  text?: string,
  areaCode?: string,
): string {
  const place = portName ?? text;
  if (!place) return areaCode ?? '—';
  return areaCode ? `${place} (${areaCode})` : place;
}

/** "1–15 Sep", "from 1 Sep", or the words the email used when it gave no dates. */
export function formatLaycan(from?: string, to?: string, text?: string): string {
  const d = (iso: string) =>
    new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
  if (from && to) return `${d(from)} – ${d(to)}`;
  if (from) return `from ${d(from)}`;
  if (to) return `until ${d(to)}`;
  return text ?? '—';
}
