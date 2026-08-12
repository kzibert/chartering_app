/**
 * Filter dropdown options shared by the vessel/company/contact search forms.
 *
 * The "all" entry is '' rather than undefined on purpose: cleanParams drops empty
 * strings, so it reaches the API as no filter at all, while the select still shows a
 * definite choice. Clearing via the little x left the control looking blank and made
 * "show me both" feel like an absent state rather than a selectable one.
 */
export const CONFIRMED_OPTIONS = [
  { value: '', label: 'All' },
  { value: true, label: 'Confirmed' },
  { value: false, label: 'Needs confirm' },
];
