/**
 * Hold capacity in cubic metres or cubic feet — the same rule the API reads circulars with
 * (`CapacityUnits`), for the forms.
 *
 * The columns are cubic metres. A hold carries roughly 1.0–1.7 m³ per tonne of deadweight
 * (this fleet: nearly all of it between 0.7 and 2.6), and cubic feet are thirty-five times
 * larger, so a figure set against the ship's size says which unit it is in. Kept in step with
 * the Java by hand: the band and the factor are the whole of it.
 */

export const CBFT_PER_M3 = 35.3146667;

const MIN_M3_PER_TONNE = 0.7;
const MAX_M3_PER_TONNE = 2.6;

export type CapacityUnit = 'm3' | 'cbft';

export const toM3 = (cbft: number) => cbft / CBFT_PER_M3;
export const toCbft = (m3: number) => m3 * CBFT_PER_M3;

const positive = (n?: number | null) => (n != null && n > 0 ? n : undefined);

/**
 * The unit a capacity must be in for a hull this size to hold it, or undefined when the size
 * cannot say — no deadweight on the form, or a figure absurd in both units.
 */
export function unitBySize(
  value?: number | null,
  dwt?: number | null,
  dwcc?: number | null,
): CapacityUnit | undefined {
  const size = positive(dwt) ?? positive(dwcc);
  const v = positive(value);
  if (!v || !size) return undefined;
  const perTonne = v / size;
  if (perTonne >= MIN_M3_PER_TONNE && perTonne <= MAX_M3_PER_TONNE) return 'm3';
  const asM3 = perTonne / CBFT_PER_M3;
  if (asM3 >= MIN_M3_PER_TONNE && asM3 <= MAX_M3_PER_TONNE) return 'cbft';
  return undefined;
}

export const unitSign = (unit: CapacityUnit) => (unit === 'm3' ? 'm³' : 'cbft');
