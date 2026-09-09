/**
 * The capacities a company can act in on a hull, as the Intake tab has to explain them.
 *
 * <b>Shared because two screens now ask the same question.</b> The card on a review item asks
 * it about a hull already on file; the modal behind "Not this ship — create her" asks it about
 * a hull that does not exist yet. Same three answers, same consequences, and a second copy of
 * the wording would drift — one of them would end up describing `owner` as harmless.
 *
 * The hints are longer than {@link ROLE_HINT} in components/VesselRoleTag on purpose. There
 * the reader is looking at a link that already exists and wants to know what it means; here
 * they are about to create one off the back of an email, and the thing worth saying is what it
 * will displace.
 */

/** The capacity words, for printing a link that already exists. */
export const ROLE_WORDS: Record<string, string> = {
  owner: 'owner',
  exclusive_broker: 'exclusive broker',
  broker: 'broker',
};

/**
 * <b>Owner is not one of the broker roles and the difference is not cosmetic.</b> Choosing it
 * displaces whoever is on the record as owner, which reassigns the ship. The two broker roles
 * sit alongside ownership and say who is working her — which is the fact a position list
 * usually carries, since the broker sending the list is very often not the owner.
 *
 * Broker first, because it is the right answer nearly every time and a list of options should
 * open on the one the reader probably wants.
 */
export const CAPACITIES = [
  {
    value: 'broker',
    label: 'Broker',
    hint: 'Works this vessel. Sits alongside the owner and displaces nothing — the ordinary answer for a broker whose list this came from.',
  },
  {
    value: 'exclusive_broker',
    label: 'Exclusive broker',
    hint: 'Works her exclusively. Only one per vessel: whoever held it is demoted to broker rather than the save failing.',
  },
  {
    value: 'owner',
    label: 'Owner',
    hint: 'Displaces the owner currently on the record. Choose this only if you know the ship has changed hands — it is a claim about who owns her, not about who sent the email.',
  },
];

/** The capacity a screen should open on. See above for why it is not `owner`. */
export const DEFAULT_CAPACITY = 'broker';
