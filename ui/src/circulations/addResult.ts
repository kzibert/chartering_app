import type { MessageInstance } from 'antd/es/message/interface';
import type { AddEntriesResult } from '../api/types';

/** How many unusable addresses to name before falling back to "and N more". */
const NAMED = 3;

/**
 * Reports a bulk add: one success line, plus a warning naming the addresses that were
 * dropped as unusable.
 *
 * <p>The warning is separate and deliberately loud. Those rows are dirt in the contact
 * data — an address with a space in it, a trailing pipe from an import — and the only
 * place anyone finds out about them is here, at the moment they fail to be circulated to.
 * Naming them is what makes the contact record fixable; a bare count would leave the user
 * hunting through several hundred addresses for two.
 *
 * @param target what the addresses went into, as it should read mid-sentence:
 *               "the current list", '"Handysize owners"'
 */
export function reportAdd(message: MessageInstance, r: AddEntriesResult, target: string) {
  message.success(
    `Added ${r.added} ${plural(r.added)} to ${target}` +
      (r.skipped ? ` (${r.skipped} already there)` : ''),
  );
  if (r.invalid) {
    const named = r.invalidEmails.slice(0, NAMED).join(', ');
    const rest = r.invalidEmails.length - NAMED;
    message.warning(
      `Skipped ${r.invalid} unusable ${plural(r.invalid)}` +
        (named ? `: ${named}${rest > 0 ? ` and ${rest} more` : ''}` : '') +
        '. Fix the contact record to circulate to them.',
      8,
    );
  }
}

function plural(n: number) {
  return n === 1 ? 'address' : 'addresses';
}
