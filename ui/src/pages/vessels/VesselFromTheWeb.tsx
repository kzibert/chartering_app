import { message } from 'antd';
import LookupCard from '../../components/LookupCard';
import { useVesselMutations } from '../../api/hooks';
import type { VesselLookupResponse, VesselResponse } from '../../api/types';

/**
 * The web card on a ship's own record.
 *
 * <b>Why her record needs this when the review queue already has it.</b> The queue searches
 * because an email raised a question; most hulls here were never the subject of one. A ship
 * opened to be worked on — quoted, offered, put on a list — is exactly where somebody notices
 * that her IMO is blank or her deadweight is the round number a broker once said, and the
 * desk's own answer has always been to type her name into a ship database in another tab. This
 * is that, with the answer beside the record it is about and the comparison already made.
 *
 * <b>Never on its own initiative.</b> The unattended pass works the review queue and nothing
 * else: a sweep over four thousand hulls is precisely the traffic this feature is careful not
 * to send at somebody else's server, which is also why nothing here searches when the card
 * merely appears. A search happens when somebody presses the button.
 *
 * <b>Five fields, and the shortness is the design.</b> IMO, deadweight, year built, flag and
 * type — what a public database states about the ship herself. Draft, capacities, gear and
 * fittings are deliberately absent: a tracking page's draught is the AIS-reported loaded figure
 * and the column it would land in is a design maximum, which is invisible and wrong in the
 * direction that loses cargoes.
 *
 * Writing is its own action with its own change set, so her History tab can say months later
 * which figure came off a public page and from which one.
 */
export default function VesselFromTheWeb({
  vessel,
  lookup,
}: {
  vessel: VesselResponse;
  /** The last search run from her record. Absent until somebody runs one. */
  lookup?: VesselLookupResponse;
}) {
  const { lookup: runLookup, applyLookup } = useVesselMutations();

  return (
    <LookupCard
      lookup={lookup}
      searching={runLookup.isPending}
      applying={applyLookup.isPending}
      // Her record exists — that is what this card is sitting on — so there is always
      // somewhere to write to.
      canApply
      onSearch={() =>
        runLookup.mutate(vessel.id, {
          onSuccess: (row) =>
            message.info(
              row?.status === 'OK'
                ? `Found ${row.matched?.name ?? 'a match'}.`
                : 'Nothing matched confidently enough to offer.',
            ),
          onError: () =>
            message.error('The search could not be run. The source may be unreachable.'),
        })
      }
      onApply={(fields) =>
        applyLookup.mutate(
          { id: vessel.id, fields },
          {
            onSuccess: () => message.success('Written, and recorded as coming from the web.'),
            // The refusal worth reading is the IMO already on another hull: that is the same
            // ship under another name, which is the most valuable thing this finds. The
            // server says which hull and why, so its words are better than any of ours.
            onError: (e: unknown) =>
              message.error(
                (e as { response?: { data?: { message?: string } } })?.response?.data?.message ??
                  'Those figures could not be written.',
              ),
          },
        )
      }
      emptyHint={
        <>
          Not looked up. Nothing is searched for automatically from here — this reads a public
          page at somebody else&rsquo;s expense, so it runs only when you ask.
        </>
      }
      onFileHint={(name) =>
        `Another vessel here already carries this IMO${name ? `, as ${name}` : ''}. That means the two records are one ship under two names — merge them rather than writing the number onto a second record. Untick IMO to take the other figures.`
      }
    />
  );
}
