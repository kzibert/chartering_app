import type { AnalysisLabel, AnalysisStatus } from '../../api/analysis';

/**
 * How the two axes read on screen, in one place.
 *
 * The tab draws each of them in three spots — the counters, the list rows, the review form
 * — and a label whose colour or wording differs between them is a label the eye stops
 * trusting. One definition, three consumers.
 */

export const LABELS: { value: AnalysisLabel; label: string; colour: string; hint: string }[] = [
  {
    value: 'UNLABELLED',
    label: 'Unlabelled',
    colour: 'default',
    hint: 'Captured, nobody has said what it is yet',
  },
  {
    value: 'CARGO_OFFER',
    label: 'Cargo offer',
    colour: 'blue',
    hint: 'A cargo on offer: stem, load and discharge, laycan, terms',
  },
  {
    value: 'VESSEL_OPENING',
    label: 'Vessel opening',
    colour: 'green',
    hint: 'A vessel position: where she opens, when, and what she is',
  },
  {
    value: 'BOTH',
    label: 'Both',
    colour: 'purple',
    // Not a hedge for "unsure" — the daily circular that carries a page of each is the
    // single most common thing in this inbox, and forcing it into one of the two would
    // teach the model to ignore whichever half lost.
    hint: 'One circular carrying cargoes and open tonnage together',
  },
  {
    value: 'OTHER',
    label: 'Other',
    colour: 'orange',
    hint: 'Neither — a fixture report, a negotiation, an invoice, an auto-reply',
  },
];

export const STATUSES: {
  value: AnalysisStatus;
  label: string;
  colour: string;
  hint: string;
}[] = [
  { value: 'NEW', label: 'New', colour: 'default', hint: 'Captured, not reviewed' },
  {
    value: 'READY',
    label: 'Ready',
    colour: 'green',
    hint: 'Reviewed and fit to train on — the only status the export reads',
  },
  {
    value: 'SKIPPED',
    label: 'Skipped',
    colour: 'red',
    // Kept rather than deleted: a deleted sample comes back on the next capture over the
    // same folder and has to be judged all over again.
    hint: 'Rejected, but kept so the next capture does not bring it back',
  },
];

export const labelMeta = (value: AnalysisLabel) =>
  LABELS.find((l) => l.value === value) ?? LABELS[0];

export const statusMeta = (value: AnalysisStatus) =>
  STATUSES.find((s) => s.value === value) ?? STATUSES[0];
