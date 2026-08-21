import type { ContactResponse } from '../api/types';

/**
 * Turning a stored phone number into a wa.me link.
 *
 * There is no way to ask WhatsApp whether a number is registered — the Business API's
 * contact check is not open to us — so the app opens a chat and lets the user see for
 * themselves. Everything here is in service of that one link.
 */

/** A number as wa.me wants it, plus whatever is doubtful about it. */
export interface WhatsappNumber {
  /** Digits only, no plus. Empty when there was nothing dialable in the value. */
  digits: string;
  /** Non-null when the number is probably not what WhatsApp needs. The link still opens. */
  warning: string | null;
}

/**
 * Strip a stored number down to the international digits wa.me expects.
 *
 * The data is thirty years of hand-typed formats — `+38-050-472-44-19`, `+49 (0) 521 …`,
 * `002-0122-110-37-18`, `0216 333 20 00 - 2419` — so this cannot be a validator; it is a
 * best effort that says when it is unsure. Two conventions are unwound because both mean
 * something precise:
 *
 * - a leading `00` is the international access prefix, the dialled spelling of `+`, so it
 *   comes off and what follows is already a full international number;
 * - a `(0)` is a national trunk digit spelled out for a domestic caller, and is never
 *   dialled from abroad.
 *
 * The doubtful cases are flagged rather than blocked: the user is about to look at the
 * result in WhatsApp anyway, which is a better check than any rule here, and refusing to
 * open the link would only hide the number that needs fixing.
 */
export function toWhatsappNumber(value: string): WhatsappNumber {
  const digits = value
    .replace(/\(\s*0\s*\)/g, '')
    .replace(/\D/g, '')
    .replace(/^00/, '');
  return { digits, warning: warn(digits) };
}

function warn(digits: string): string | null {
  if (!digits) return 'No digits in this number, so there is nothing to open.';
  // A single leading 0 is a trunk prefix, which only means anything inside its own country
  // — the international 00 has already been unwound by the time this runs.
  if (digits.startsWith('0')) {
    return 'Starts with 0, so it looks like a national number with no country code — '
      + 'WhatsApp needs the full international number. Edit the contact to add it.';
  }
  // E.164 allows 15 digits; under 8 is too short for any country code plus a subscriber
  // number, and over 15 usually means an extension was typed into the same field.
  if (digits.length < 8) return `Only ${digits.length} digits — too short to be a full number.`;
  if (digits.length > 15) {
    return `${digits.length} digits — longer than any real number, so an extension or a `
      + 'second number may have been typed into the same field.';
  }
  return null;
}

const TOKEN = /\{\{\s*(\w+)\s*\}\}/g;
const GREETING_FALLBACK = 'Sirs';
/** The opener for a number with no name on file — see MailTemplateService.salutation. */
const NEUTRAL_SALUTATION = 'Good day';

/**
 * Substitute `{{greeting}}` and friends from the contact on the row.
 *
 * Done here rather than on the server because the browser already holds the contact, and
 * the same fallbacks the circulars use apply — greeting name, then the person's name, then
 * "Sirs" for a number filed against a company with nobody's name on it. An unknown
 * placeholder is left verbatim, exactly as MailTemplateService leaves it, so a typo shows
 * up in the message instead of silently vanishing.
 */
export function renderWhatsappMessage(template: string, ct: ContactResponse): string {
  return template.replace(TOKEN, (whole, key: string) => {
    switch (key.toLowerCase()) {
      case 'salutation': {
        const name = firstNonBlank(ct.greetingName, ct.personName);
        return name ? `Dear ${name}` : NEUTRAL_SALUTATION;
      }
      case 'greeting':
        return firstNonBlank(ct.greetingName, ct.personName) || GREETING_FALLBACK;
      case 'name':
        return firstNonBlank(ct.personName, ct.greetingName);
      case 'title':
        return firstNonBlank(ct.title);
      case 'company':
        return firstNonBlank(ct.companyName);
      default:
        return whole;
    }
  });
}

/** `https://wa.me/<digits>?text=<message>`. The message is dropped when it is blank. */
export function whatsappLink(digits: string, message: string): string {
  const base = `https://wa.me/${digits}`;
  return message.trim() ? `${base}?text=${encodeURIComponent(message)}` : base;
}

function firstNonBlank(...values: (string | undefined)[]): string {
  for (const v of values) {
    if (v && v.trim()) return v.trim();
  }
  return '';
}
