/**
 * Derives the English greeting name from a full name, for when the field is left blank.
 *
 * The rules were read off the 3405 greetings already in the database rather than invented:
 * 85% are the first word of the full name, and almost all of the rest are explained by a
 * leading honorific ("Cpt. Levent Cum" -> Levent), by Slavic surname-first ordering
 * ("Zdobnov Roman" -> Roman), or by transliteration ("Сергей" -> Sergey).
 *
 * It is a suggestion, not an oracle — the form fills the field so the value is visible
 * and editable before saving. Some conventions are simply unguessable ("Nils Ole
 * Andersen" is greeted as Ole), and a wrong guess in a circular is worse than a blank,
 * so anything that does not look like a person's name resolves to '' instead.
 */

/** Matches the transliteration style already used in the data (Сергей -> Sergey, Михаил -> Mikhail). */
const CYRILLIC: Record<string, string> = {
  а: 'a', б: 'b', в: 'v', г: 'g', ґ: 'g', д: 'd', е: 'e', ё: 'yo', є: 'ye', ж: 'zh',
  з: 'z', и: 'i', і: 'i', ї: 'yi', й: 'y', к: 'k', л: 'l', м: 'm', н: 'n', о: 'o',
  п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f', х: 'kh', ц: 'ts', ч: 'ch', ш: 'sh',
  щ: 'shch', ъ: '', ы: 'y', ь: '', э: 'e', ю: 'yu', я: 'ya',
};

const HONORIFICS = new Set([
  'mr', 'mrs', 'ms', 'miss', 'mister', 'madam', 'madame', 'sir', 'sirs',
  'capt', 'cpt', 'cap', 'captain', 'kapt', 'kpt', 'dr', 'doctor',
  'eng', 'engineer', 'prof', 'professor', 'г-н', 'госпожа', 'господин',
]);

/** Words that mark a row as a company or a role rather than a person. */
const COMPANY_WORDS = new Set([
  'shipping', 'maritime', 'marine', 'trading', 'trade', 'agency', 'agencies', 'agent',
  'chartering', 'logistics', 'transport', 'denizcilik', 'brokers', 'brokerage',
  'co', 'co.', 'ltd', 'ltd.', 'llc', 'inc', 'gmbh', 'srl', 'sa', 's.a.', 'as', 'a.s.',
  'group', 'company', 'corp', 'corporation', 'lines', 'line',
]);

/** Job words that sometimes trail a name ("Mr. Noorifard operation"). */
const ROLE_WORDS = new Set([
  'operation', 'operations', 'ops', 'chartering', 'charterer', 'broker', 'sales',
  'manager', 'director', 'office', 'desk', 'dept', 'department', 'team', 'person', 'name',
]);

/** Whole names that are salutations or placeholders, never a person. */
const NOT_A_NAME = /^(sir|sirs|dear\s+sirs?|madam|to\s+whom.*|name\s+person|n\/?a|unknown|\?+|-+)$/i;

const PATRONYMIC = /(ovich|evich|ovna|evna|evish|ivich|yich|ович|евич|овна|евна|ich)$/i;

/**
 * Only these are safe to read as a patronymic when the token comes *first*, where the
 * position is no help. Bare -ich would swallow given names like Friedrich.
 */
const PATRONYMIC_STRONG = /(ovich|evich|ovna|evna|ович|евич|овна|евна)$/i;

/**
 * Surname endings distinctive enough to imply surname-first ordering.
 *
 * This list was measured against the existing greetings, not guessed — each candidate
 * was scored on how often it picks the right token versus the wrong one. The rejects
 * are instructive: -ina never helped and misfired 29 times (Alevtina, Irina, Katerina),
 * -uk lost to Ufuk and Faruk, -yan to Stoyan and Demyan, and a bare -ko to Marko. Only
 * -enko earns its place among the -ko endings.
 */
const SURNAME_SUFFIX = /(ov|ev|ova|eva|sky|ski|skiy|skyy|enko|ов|ев|ова|ева|ский|енко|ко)$/i;

const hasCyrillic = (s: string) => /[Ѐ-ӿ]/.test(s);

function transliterate(word: string): string {
  return [...word]
    .map((ch) => {
      const lower = ch.toLowerCase();
      const mapped = CYRILLIC[lower];
      if (mapped === undefined) return ch;
      // Preserve the original capitalisation of the first letter of a multi-char mapping.
      return ch === lower ? mapped : mapped.charAt(0).toUpperCase() + mapped.slice(1);
    })
    .join('');
}

const titleCase = (word: string) =>
  word.charAt(0).toUpperCase() + word.slice(1).toLowerCase();

/**
 * Strip anything glued to the front with a dot: a honorific ("Mr.Atakan" -> Atakan) or
 * an initial ("M.Alev Tunc" -> Alev).
 */
function splitGluedPrefix(token: string): string {
  // Repeatedly, so "Capt.A.Guslev" sheds both the rank and the initial.
  let out = token;
  for (let i = 0; i < 4; i++) {
    const honorific = out.match(/^(mr|mrs|ms|miss|capt|cpt|kapt|dr|eng|prof)\.(.+)$/i);
    if (honorific) { out = honorific[2]; continue; }
    const initial = out.match(/^\p{L}\.(\p{L}.*)$/u);
    if (initial) { out = initial[1]; continue; }
    break;
  }
  return out;
}

const isJunkToken = (t: string) =>
  !/\p{L}/u.test(t) ||                    // numbers, punctuation, "+40239616149"
  t.includes('@') ||                      // an address pasted into the name
  /^\p{L}$/u.test(t) ||                   // a bare initial: "G. Figari" is greeted Figari
  ROLE_WORDS.has(t.toLowerCase()) ||
  COMPANY_WORDS.has(t.toLowerCase());

export function resolveGreeting(fullName?: string): string {
  const full = (fullName ?? '').replace(/\s+/g, ' ').trim();
  if (!full || NOT_A_NAME.test(full)) return '';

  // "FORA Shipping & Trading Co." is a company; "AGENCY Mr. Kurtulus Kondur" is a person
  // filed under one, and the honorific is what tells them apart.
  const words = full.split(/[\s,;/&+]+/).filter(Boolean);
  const honorificPresent = words.some(
    (t) => HONORIFICS.has(t.toLowerCase().replace(/[.,]$/, '')) || /^(mr|mrs|ms|capt|cpt|kapt|dr)\./i.test(t),
  );
  if (words.some((t) => COMPANY_WORDS.has(t.toLowerCase())) && !honorificPresent) return '';

  // A comma after the honorific ("mr, Kingo") is punctuation, not a second person, so
  // shed leading honorifics before treating separators as "and another person".
  const deTitled = full.replace(
    /^((mr|mrs|ms|miss|mister|madam|capt|cpt|kapt|cap|dr|eng|prof)[.,]?\s+)+/i,
    '',
  );

  // Several people share one row ("Andrey & Soren", "Alexey, Felix Petrovich"); the
  // greeting on file is always the first of them.
  const name = deTitled.split(/\s*[,;&+]\s*|\s+and\s+/i)[0].trim();

  const tokens = name
    .split(/[\s/]+/)
    .map(splitGluedPrefix)
    .map((t) => t.replace(/^[("'.,]+|[)"'.,]+$/g, ''))
    .filter((t) => t && !HONORIFICS.has(t.toLowerCase()) && !isJunkToken(t));

  if (tokens.length === 0) return '';

  const pick = chooseGivenName(tokens);
  return titleCase(hasCyrillic(pick) ? transliterate(pick) : pick);
}

function chooseGivenName(tokens: string[]): string {
  // A patronymic never leads, and the given name sits immediately before it:
  // "Egorov Mikhail Dmitrievich" -> Mikhail.
  const patronymicAt = tokens.findIndex((t, i) => i > 0 && PATRONYMIC.test(t));
  if (patronymicAt > 0) return tokens[patronymicAt - 1];

  // Leading -ovich is a surname, not a patronymic: "Maksimovich Vladislav" -> Vladislav.
  if (tokens.length >= 2 && PATRONYMIC_STRONG.test(tokens[0])) return tokens[1];

  // Surname-first ordering: "Bondarenko Iryna" -> Iryna.
  if (tokens.length >= 2 && SURNAME_SUFFIX.test(tokens[0]) && !SURNAME_SUFFIX.test(tokens[1])) {
    return tokens[1];
  }

  return tokens[0];
}
