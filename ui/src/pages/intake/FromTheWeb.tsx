import { message } from 'antd';
import LookupCard from '../../components/LookupCard';
import { useIntakeMutations } from '../../intake/store';
import type { IntakeItemResponse } from '../../api/intake';

/**
 * The web card as a review item sees it.
 *
 * <b>Thin on purpose.</b> The card itself is shared with the vessel's own record — see
 * {@link LookupCard} for why the evidence and the confidence wording must read identically in
 * both places. What belongs here is only what is true of a review item: that hulls waiting in
 * this queue are searched for unattended, that a search can be recorded as not worth making,
 * and that there may be no hull to write to yet because she has not been created.
 */
export default function FromTheWeb({
  item,
  vesselId,
  chosen,
  onChange,
}: {
  item: IntakeItemResponse;
  /** The hull to write to. Absent on a new vessel until she has been created. */
  vesselId?: number;
  chosen?: string[];
  onChange?: (fields: string[]) => void;
}) {
  const { lookup: runLookup, applyLookup } = useIntakeMutations();
  const target = vesselId ?? item.vesselId;

  return (
    <LookupCard
      lookup={item.lookup}
      searching={runLookup.isPending}
      applying={applyLookup.isPending}
      canApply={target != null}
      chosen={chosen}
      onChange={onChange}
      onSearch={() =>
        runLookup.mutate(item.id, {
          onSuccess: (updated) =>
            message.info(
              updated.lookup?.status === 'OK'
                ? `Found ${updated.lookup.matched?.name ?? 'a match'}.`
                : updated.lookup?.status === 'SKIPPED'
                  ? 'She is already identified here and in the email — a search could only agree.'
                  : 'Nothing matched confidently enough to offer.',
            ),
        })
      }
      onApply={(fields) =>
        applyLookup.mutate(
          { id: item.id, body: { fields, vesselId } },
          { onSuccess: () => message.success('Written, and recorded as coming from the web.') },
        )
      }
      emptyHint="Not looked up yet. Hulls waiting in this queue are searched for automatically; this runs one now."
      skippedHint={
        <>
          Not searched for. The email and the record both name her by IMO, so a search could only
          have agreed with them — and this reads a public page at somebody else&rsquo;s expense.
          Search anyway if you want a second opinion on her particulars.
        </>
      }
      onFileHint={(name) =>
        `A vessel here already carries this IMO${name ? `, as ${name}` : ''}. An IMO is identity, so she is not a new hull — she has been renamed. This item is being turned into a particulars review against that ship, and her position is filed on her; reload if it is still showing as a new vessel. Accepting the name row is what records the name from this email as her current one.`
      }
      blockedHint="Create her or link her to a ship on file first — there is no record to write to yet."
    />
  );
}
