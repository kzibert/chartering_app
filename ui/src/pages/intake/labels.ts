import type { IntakeItemKind, ParseStatus } from '../../api/intake';

/**
 * What each kind of review item is called on screen, and what it is asking.
 *
 * The hints are not tooltips for their own sake. The three items look similar in a list and
 * want quite different answers — one creates a record, one edits one, one folds two into
 * one — and a queue whose rows are indistinguishable is a queue answered carelessly.
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
];

export const parseStatusMeta = (status: ParseStatus) =>
  PARSE_STATUSES.find((s) => s.value === status) ?? {
    value: status,
    label: status,
    colour: 'default',
    hint: '',
  };
