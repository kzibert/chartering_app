import { CBFT_PER_M3 } from '../../vessels/capacity';
import type { FieldDiff } from '../../api/intake';

/**
 * The third answer on a review row: neither the record nor the email, but a value typed.
 *
 * <b>Why the screen needed one.</b> The two answers it had were "keep what we have" and "the
 * email is right", and a broker's list is regularly wrong in a way that does not make the
 * record right either — a capacity quoted in the wrong unit, a gear description garbled, a flag
 * out of date on both sides. Answering that took two visits: discard the item, then go and edit
 * the hull. The second half is the one that gets forgotten.
 *
 * Kept out of the drawer because the unit arithmetic below is worth testing and reading on its
 * own, and because the drawer is already the longest file on this tab.
 */

/** Which fields hold a capacity — the ones with a unit worth arguing about. */
export function isCapacityField(field: string): boolean {
  return field === 'grainCapacityM3' || field === 'baleCapacityM3';
}

/** The extraction's own figure for a capacity field, before the API read a unit into it. */
function rawFigure(field: string, reading?: Record<string, unknown>): number | undefined {
  const key = field === 'grainCapacityM3' ? 'grainCapacity' : 'baleCapacity';
  const value = reading?.[key];
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) && n > 0 ? n : undefined;
}

/** The number out of "8240 m3", or undefined when the text is not one. */
export function figureIn(text?: string): number | undefined {
  if (!text) return undefined;
  const n = Number(String(text).replace(/,/g, '').replace(/[^\d.-]/g, ''));
  return Number.isFinite(n) ? n : undefined;
}

/**
 * Which unit the API read this capacity in, judged by comparing what it produced against what
 * the email actually printed.
 *
 * <b>Derived rather than sent.</b> The payload already carries the whole extraction, so both
 * readings of the figure are computable here; a field on the response saying which one was
 * taken would be a second copy of a decision the API makes, free to disagree with it.
 *
 * Undefined when there is no raw figure to compare against — an item raised by an older build,
 * or a field that is not a capacity at all.
 */
export function readAs(
  row: FieldDiff,
  reading?: Record<string, unknown>,
): 'm3' | 'cbft' | undefined {
  if (!isCapacityField(row.field)) return undefined;
  const raw = rawFigure(row.field, reading);
  const shown = figureIn(row.incoming);
  if (raw == null || shown == null) return undefined;
  // Within a tonne of the printed figure means it was taken as cubic metres and written
  // through unchanged; anything far below it has been divided by thirty-five.
  return Math.abs(shown - raw) <= 1 ? 'm3' : 'cbft';
}

/**
 * The same figure read in the other unit, for the button that says "no, it is cbft".
 *
 * Rounded to whole cubic metres, like the API's own conversion: capacities are quoted whole in
 * either unit, and "6937.62742" offered on a review screen reads like something somebody
 * measured.
 */
export function otherReading(
  row: FieldDiff,
  reading?: Record<string, unknown>,
): { unit: 'm3' | 'cbft'; value: string } | undefined {
  const current = readAs(row, reading);
  const raw = rawFigure(row.field, reading);
  if (!current || raw == null) return undefined;
  return current === 'm3'
    ? { unit: 'cbft', value: String(Math.round(raw / CBFT_PER_M3)) }
    : { unit: 'm3', value: String(raw) };
}

/**
 * What to send as corrections: only rows being accepted, and only where the typed value is
 * actually different from what the email said.
 *
 * A box left empty means "use the email's value", which is the common answer — sending it as a
 * correction would make every accept look like an override in the history, and would record the
 * email's own figure as one the desk had declined.
 */
export function correctionsFrom(
  rows: FieldDiff[],
  chosen: string[],
  edits: Record<string, string>,
): Record<string, string> | undefined {
  const out: Record<string, string> = {};
  for (const row of rows) {
    if (!chosen.includes(row.field)) continue;
    const typed = (edits[row.field] ?? '').trim();
    if (!typed) continue;
    if (typed === (row.incoming ?? '').trim()) continue;
    out[row.field] = typed;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}
