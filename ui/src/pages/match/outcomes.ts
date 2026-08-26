import type { MatchOutcome } from '../../api/types';

/**
 * What a broker can say about a pairing, and what saying it does.
 *
 * `closes` is the load-bearing field. A pairing that is closed drops below the fresh
 * suggestions rather than sitting at the top of the list every morning being scrolled past
 * — which is the whole reason these are recorded rather than computed.
 */
export const OUTCOME_META: Record<
  MatchOutcome,
  { label: string; color: string; closes: boolean; hint: string }
> = {
  SHORTLISTED: {
    label: 'Shortlisted',
    color: 'gold',
    closes: false,
    hint: 'Worth working, not yet put to anyone',
  },
  OFFERED: {
    label: 'Offered',
    color: 'blue',
    closes: false,
    hint: 'Put to the charterer or the owner',
  },
  DECLINED: {
    label: 'Declined',
    color: 'default',
    closes: true,
    hint: 'Somebody said no. Drops below the fresh suggestions.',
  },
  FIXED: {
    label: 'Fixed',
    color: 'green',
    closes: true,
    hint: 'This is the one that fixed',
  },
  DISMISSED: {
    label: 'Not suitable',
    color: 'default',
    closes: true,
    hint: 'Not this ship for this cargo, whatever the score said. Stops her being suggested again.',
  },
};

export const OUTCOMES = Object.keys(OUTCOME_META) as MatchOutcome[];

export const OUTCOME_OPTIONS = OUTCOMES.map((value) => ({
  value,
  label: OUTCOME_META[value].label,
}));

/**
 * How a score reads at a glance.
 *
 * Deliberately coarse. The number is a weighted fraction of what could be checked, not a
 * probability of fixing, and three bands is as much precision as it can honestly carry —
 * finer shading would invite reading an 82 as meaningfully better than a 79.
 */
export function scoreColor(score: number): string {
  if (score >= 80) return '#389e0d';
  if (score >= 50) return '#d46b08';
  return '#8c8c8c';
}
