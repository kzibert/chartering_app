import { Typography } from 'antd';
import { useCargo } from '../../api/hooks';
import { useCargoSources } from '../../intake/store';
import type { CargoSourceResponse } from '../../api/intake';
import { OriginalEmailView } from '../../components/OriginalEmail';
import type { EmailSource } from '../../components/OriginalEmail';
import CargoDetails from './CargoDetails';
import MatchDrawer from '../match/MatchDrawer';
import type { CargoResponse } from '../../api/types';

/**
 * The emails a cargo can be read in, newest first.
 *
 * Only the ones the mailbox still holds can be opened. A cargo somebody typed has none.
 *
 * Falling back to the cargo's own column matters more than it looks: cargo_sources is the
 * newer per-arrival table and older rows have only source_mail_message_id on the cargo
 * itself, so without it the email would be missing on exactly the cargoes that have been
 * here longest. A cargo with both lists the sources, which are the fuller answer.
 */
export function cargoEmailSources(
  cargo: CargoResponse | undefined,
  sources: CargoSourceResponse[] | undefined,
): EmailSource[] {
  const held = (sources ?? []).filter((s) => s.mailMessageId != null || s.feedItemId != null);
  if (held.length > 0) {
    return held.map((s, i) => ({
      mailMessageId: s.mailMessageId,
      feedItemId: s.feedItemId,
      label: s.companyName ?? s.personName ?? s.fromAddress ?? s.feedSourceName,
      when: s.reportedAt,
      current: i === 0,
    }));
  }
  if (cargo?.sourceMailMessageId != null) return [{ mailMessageId: cargo.sourceMailMessageId, current: true }];
  if (cargo?.sourceFeedItemId != null) return [{ feedItemId: cargo.sourceFeedItemId, current: true }];
  return [];
}

/**
 * Every ship that suits one cargo, with the cargo and its email kept on the left: each score
 * on the right is an argument about figures a broker typed, and that is where they can be
 * checked. Opened from the cargo's drawer and from its row on the Cargoes tab, so it loads
 * the cargo itself - both queries are the drawer's own, and come out of the cache there.
 */
export default function CargoMatchWindow({
  cargoId,
  onClose,
}: {
  /** Open while set. */
  cargoId?: number;
  onClose: () => void;
}) {
  const { data } = useCargo(cargoId);
  const { data: sources } = useCargoSources(cargoId);
  if (cargoId == null || !data) return null;

  return (
    <MatchDrawer
      open
      side="cargo"
      cargoId={cargoId}
      subject={data.commodity}
      onClose={onClose}
      aside={
        <>
          <CargoDetails cargo={data} hasSources={(sources?.length ?? 0) > 0} />
          <Typography.Title level={5} style={{ marginTop: 24 }}>
            Original email
          </Typography.Title>
          <OriginalEmailView sources={cargoEmailSources(data, sources)} height="60vh" />
        </>
      }
    />
  );
}
