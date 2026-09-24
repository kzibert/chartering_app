import type { IntakeItemKind, ParseStatus } from '../../api/intake';

/**
 * What each kind of review item is called on screen, and what it is asking.
 *
 * The hints are not tooltips for their own sake. The four items look similar in a list and
 * want quite different answers — one creates a record, one edits one, one folds two into one,
 * one is about the firm that signed rather than about the market — and a queue whose rows are
 * indistinguishable is a queue answered carelessly.
 */
export const KINDS: {
  value: IntakeItemKind;
  label: string;
  colour: string;
  hint: string;
}[] = [
  {
    value: 'NEW_VESSEL',
    label: 'New vessel',
    colour: 'geekblue',
    hint: 'A position naming a hull with no match on IMO, name or former name. Create her, or point it at a ship already on file.',
  },
  {
    value: 'VESSEL_FIELDS',
    label: 'Particulars differ',
    colour: 'orange',
    hint: 'She is on file and the email disagrees about her. Her position has already been filed — this is only about what she is.',
  },
  {
    value: 'CARGO_MERGE',
    label: 'Duplicate cargo',
    colour: 'purple',
    hint: 'Looks like a cargo already in hand, usually because two brokers are working the same charterer. Merge and keep both senders, or keep it separate.',
  },
  {
    value: 'COMPANY_DETAILS',
    label: 'Company details',
    colour: 'cyan',
    hint: 'The signature at the foot of a circular says something the contacts database does not: a firm nobody here has met, or a record with gaps or disagreements. Nothing is written until you tick it, and nothing arrives flagged for circulation.',
  },
];

export const kindMeta = (kind: IntakeItemKind) =>
  KINDS.find((k) => k.value === kind) ?? {
    value: kind,
    label: kind,
    colour: 'default',
    hint: '',
  };

/** How the model classified an email. Its own words, not a mapping. */
export const EMAIL_TYPES: Record<string, { label: string; colour: string }> = {
  cargo_offer: { label: 'Cargo offer', colour: 'blue' },
  vessel_opening: { label: 'Open position', colour: 'green' },
  mixed: { label: 'Both', colour: 'purple' },
  other: { label: 'Neither', colour: 'default' },
};

export const PARSE_STATUSES: {
  value: ParseStatus;
  label: string;
  colour: string;
  hint: string;
}[] = [
  { value: 'PARSED', label: 'Read', colour: 'green', hint: 'The model answered and the answer was understood.' },
  {
    value: 'FAILED',
    label: 'Failed',
    colour: 'red',
    hint: 'The server did not answer, or the answer could not be read. Retried automatically until the attempt ceiling; "Read again" resets it.',
  },
  {
    value: 'SKIPPED',
    label: 'Skipped',
    colour: 'default',
    hint: 'No text body — an attachment-only list, or a calendar invite. Recorded so the sweep does not pick it up forever.',
  },
  {
    value: 'IGNORED',
    label: 'Ignored',
    colour: 'default',
    hint: 'Set by hand on a message nobody wants read: the email that defeats the model every time, the thread with no position in it. Never retried; "Read again" puts it back.',
  },
];

export const parseStatusMeta = (status: ParseStatus) =>
  PARSE_STATUSES.find((s) => s.value === status) ?? {
    value: status,
    label: status,
    colour: 'default',
    hint: '',
  };

/**
 * What "minor" means, in one place for the tag, the sub-tab and the company record.
 *
 * A minor question is real and kept, and answered by the same drawer - it only does not count
 * toward Needs review. It is recomputed as mail arrives, so it can move back onto the queue.
 */
export const MINOR_HINT =
  'Kept for the record rather than the queue: a company whose name and email addresses have not ' +
  'changed, or particulars that differ only by a rounding, by a value already decided, or ' +
  'against a record other firms confirm. It moves back to Needs review if a later email makes ' +
  'it a real question.';
