import type { ContactResponse } from '../api/types';

/**
 * Narrows a bulk-collected set of addresses down to at most N per company.
 *
 * <p>The API has already applied the per-person rule before these arrive — circ-flagged
 * addresses where a person has any, else their main, else all their working ones. That
 * rule answers "which of this person's addresses", and a company with eight people still
 * comes back with eight addresses. This answers the other question: how many of them
 * should actually receive the circular.
 *
 * <p>Candidates are ranked and the top N per company are kept:
 *
 * <pre>
 *   1. circ-flagged
 *   2. main
 *   3. everything else
 * </pre>
 *
 * <p>with the keyword list breaking ties <em>inside</em> each band rather than across
 * them. So a circ-flagged address always outranks a keyword match on an unflagged one —
 * the flags are a decision somebody made about this company, and a keyword is a guess
 * about all of them. Keywords only get to decide where the flags are silent, or where
 * they are equally loud on several addresses.
 *
 * <p>Keywords are matched against the address alone, case-insensitively, as substrings,
 * and are tried in the order they were typed: earlier keywords win. Matching the address
 * and not the person's name or title is deliberate — "chart" would otherwise pull in
 * anyone surnamed Charteris.
 */

/** Pre-filled for a first-time user; only active when the checkbox is ticked. */
export const DEFAULT_KEYWORDS = 'chartering, chart';

/**
 * No cap until somebody sets one: a bulk add takes every address the flags select, which
 * is what the button did before this setting existed. A default that silently dropped
 * addresses would change what an existing habit does.
 */
export const DEFAULT_MAX_PER_COMPANY: number | null = null;

export interface NarrowOptions {
  /** Addresses to keep per company. `null` means no cap — every address is kept. */
  maxPerCompany: number | null;
  /** Keywords are inert until this is ticked, so a stale field can't quietly reorder a send. */
  useKeywords: boolean;
  /** Raw field contents, comma-separated. Parsed here so the caller keeps what was typed. */
  keywords: string;
}

export interface NarrowResult {
  kept: ContactResponse[];
  /** Distinct people in the input — a contact with no person counts as one of its own. */
  peopleBefore: number;
  /** How many of those contribute no address at all once the cap is applied. */
  peopleDropped: number;
}

/** Split the field on commas, trim, drop blanks, lowercase, and keep the typed order. */
export function parseKeywords(raw: string): string[] {
  return raw
    .split(',')
    .map((k) => k.trim().toLowerCase())
    .filter((k) => k.length > 0);
}

export function narrowContacts(
  contacts: ContactResponse[],
  { maxPerCompany, useKeywords, keywords }: NarrowOptions,
): NarrowResult {
  const peopleBefore = countPeople(contacts);

  // With no cap there are no slots to compete for, so the ranking has nothing to decide
  // and the keyword list cannot change the outcome. Returning early keeps that honest
  // rather than reordering the set for no reason.
  if (maxPerCompany == null) {
    return { kept: contacts, peopleBefore, peopleDropped: 0 };
  }

  const kws = useKeywords ? parseKeywords(keywords) : [];

  const groups = new Map<string, ContactResponse[]>();
  contacts.forEach((c) => {
    const key = groupKey(c);
    const group = groups.get(key);
    if (group) group.push(c);
    else groups.set(key, [c]);
  });

  const kept: ContactResponse[] = [];
  groups.forEach((group) => {
    const ranked = group
      // Index carried alongside so the sort can stay stable on ties: the order the API
      // returned is the last word, and it is the same order every time.
      .map((c, i) => ({ c, i }))
      .sort((a, b) => {
        const band = bandOf(a.c) - bandOf(b.c);
        if (band !== 0) return band;
        const kw = keywordRank(a.c, kws) - keywordRank(b.c, kws);
        if (kw !== 0) return kw;
        return a.i - b.i;
      })
      .map((x) => x.c);

    // Two contact rows can carry the same address — two people sharing a desk, or a
    // duplicated import. Collapsing them first stops one address eating two slots, and
    // keeps the best-ranked row of the pair because the list is already sorted.
    const seen = new Set<string>();
    for (const c of ranked) {
      if (seen.size >= maxPerCompany) break;
      const address = c.contactValue.trim().toLowerCase();
      if (seen.has(address)) continue;
      seen.add(address);
      kept.push(c);
    }
  });

  return { kept, peopleBefore, peopleDropped: peopleBefore - countPeople(kept) };
}

/** circ beats main beats everything else. Lower sorts first. */
function bandOf(c: ContactResponse): number {
  if (c.circ) return 0;
  if (c.main) return 1;
  return 2;
}

/**
 * Position of the first keyword the address contains, so earlier keywords win. Anything
 * matching nothing ranks after everything that matched, which is also what an empty
 * keyword list produces for every address — a rank they all share, leaving the order to
 * the tiebreak below it.
 */
function keywordRank(c: ContactResponse, keywords: string[]): number {
  if (keywords.length === 0) return 0;
  const address = c.contactValue.toLowerCase();
  const hit = keywords.findIndex((k) => address.includes(k));
  return hit === -1 ? keywords.length : hit;
}

/**
 * The unit the cap is applied over. A company, where there is one — that is the whole
 * point of the setting. Addresses belonging to no company fall back to their person, and
 * then to themselves, so that a contact with neither is never merged with an unrelated
 * one and silently capped away.
 */
function groupKey(c: ContactResponse): string {
  if (c.companyId != null) return `co:${c.companyId}`;
  if (c.personId != null) return `p:${c.personId}`;
  return `x:${c.id}`;
}

/** People, counting each person-less contact as a source of its own. */
function countPeople(contacts: ContactResponse[]): number {
  return new Set(contacts.map((c) => (c.personId != null ? `p:${c.personId}` : `x:${c.id}`))).size;
}
