import { formatLaycan, formatQuantity } from './status';

/**
 * An arriving cargo as the model read it, as the lines a broker reads a cargo in.
 *
 * <b>Why this is not the cargo's own formatting.</b> The stored reading is the model's answer
 * kept as JSON on the intake item, so its keys follow the cargo fields but nothing guarantees
 * every one is present, or that a number arrived as a number. Each line therefore appears only
 * where the reading actually says something, and a missing field is simply absent rather than
 * printed as a dash that would look like a finding. The formatting itself is the cargo list's
 * (`formatQuantity`, `formatLaycan`), so a reading and the record it would merge into are read
 * the same way.
 */
export interface ReadingLine {
  label: string;
  value: string;
}

type Reading = Record<string, unknown>;

const text = (v: unknown): string | undefined =>
  typeof v === 'string' && v.trim() !== '' ? v.trim() : undefined;

const number = (v: unknown): number | undefined =>
  typeof v === 'number' && Number.isFinite(v) ? v : undefined;

/** The first of the keys that holds text, for the spellings the reading has been written under. */
const first = (r: Reading, ...keys: string[]): string | undefined => {
  for (const k of keys) {
    const v = text(r[k]);
    if (v) return v;
  }
  return undefined;
};

export function readingLines(reading: Reading | undefined): ReadingLine[] {
  if (!reading) return [];
  const lines: ReadingLine[] = [];

  const commodity = text(reading.commodity);
  if (commodity) lines.push({ label: 'Commodity', value: commodity });

  const quantity = number(reading.quantity);
  const tolerance = text(reading.quantityTolerance);
  if (quantity != null || tolerance) {
    lines.push({
      label: 'Quantity',
      value: formatQuantity(quantity, text(reading.quantityUnit), tolerance),
    });
  }

  // The reading's own keys (Extraction.ExtractedCargo): the port where one was named, else the
  // water, which is how most circulars write a discharge.
  const load = first(reading, 'loadPort', 'loadArea');
  const discharge = first(reading, 'dischargePort', 'dischargeArea');
  if (load || discharge) {
    lines.push({ label: 'Route', value: `${load ?? '—'} → ${discharge ?? '—'}` });
  }

  const from = text(reading.laycanFrom);
  const to = text(reading.laycanTo);
  const laycanText = text(reading.laycanText);
  if (from || to || laycanText) {
    lines.push({ label: 'Laycan', value: formatLaycan(from, to, laycanText) });
  }

  const charterer = text(reading.charterer);
  if (charterer) lines.push({ label: 'Charterer', value: charterer });

  return lines;
}
