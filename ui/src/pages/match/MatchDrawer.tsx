import { useState, type ReactNode } from 'react';
import { Col, Drawer, Row, Space, Switch, Tooltip, Typography } from 'antd';
import { useMatchesForCargo, useMatchesForPosition } from '../../api/hooks';
import { usePersistedState } from '../../components/usePersistedState';
import { useIsMobile } from '../../responsive/useIsMobile';
import MatchList from './MatchList';
import CargoDrawer from '../cargoes/CargoDrawer';
import CargoForm from '../cargoes/CargoForm';
import VesselDrawer from '../vessels/VesselDrawer';
import VesselForm from '../vessels/VesselForm';
import type { CargoResponse, VesselResponse } from '../../api/types';

type Props = {
  open: boolean;
  onClose: () => void;
  /** Whose name heads the drawer — the cargo or the ship the list is scored for. */
  subject: string;
  /**
   * What the list is scored for, kept beside it: the window widens and this takes the left
   * column. The cargo's full list passes the cargo and its email.
   */
  aside?: ReactNode;
} & (
  | { side: 'cargo'; cargoId: number }
  | { side: 'position'; positionId: number }
);

/**
 * The scored pairings for one record, opened from that record.
 *
 * This used to be a tab of its own, with every live cargo down the left and the tonnage for
 * whichever was picked on the right. It moved into the records because that is where the
 * question is actually asked: a broker reading a cargo wants the ships for *it*, and one
 * reading a position list's hull wants the cargoes for *her* — nobody opened Match to browse.
 * The scorer and the list are the same ones; only the way in changed.
 *
 * The other half of each pairing opens its own record on top of this one, with its own edit
 * form, the way the tab offered it. That record can in turn open its own matches, so a chain
 * of drawers is possible — each one is a question somebody asked, and closing it goes back to
 * the one before.
 */
/** The drawer's height less its header and padding. */
const columnScroll = { maxHeight: 'calc(100vh - 105px)', overflowY: 'auto' } as const;

export default function MatchDrawer(props: Props) {
  const { open, onClose, subject, side, aside } = props;
  const isMobile = useIsMobile();
  const [showRuledOut, setShowRuledOut] = usePersistedState('match.showRuledOut', false);
  const [expanded, setExpanded] = useState<number[]>([]);

  const byCargo = useMatchesForCargo(
    open && props.side === 'cargo' ? props.cargoId : undefined,
    showRuledOut,
  );
  const byPosition = useMatchesForPosition(
    open && props.side === 'position' ? props.positionId : undefined,
    showRuledOut,
  );
  const query = side === 'cargo' ? byCargo : byPosition;

  const [cargoDrawerId, setCargoDrawerId] = useState<number>();
  const [cargoFormOpen, setCargoFormOpen] = useState(false);
  const [editingCargo, setEditingCargo] = useState<CargoResponse | null>(null);
  const [vesselDrawerId, setVesselDrawerId] = useState<number>();
  const [vesselFormOpen, setVesselFormOpen] = useState(false);
  const [editingVessel, setEditingVessel] = useState<VesselResponse | null>(null);

  const toggleExpanded = (id: number) =>
    setExpanded((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  const list = (
    <MatchList
      matches={query.data ?? []}
      loading={query.isLoading}
      side={side}
      expanded={expanded}
      onToggleExpanded={toggleExpanded}
      onOpenCargo={setCargoDrawerId}
      onOpenVessel={setVesselDrawerId}
    />
  );

  return (
    <Drawer
      open={open}
      onClose={onClose}
      width={aside ? '92vw' : 720}
      title={side === 'cargo' ? `Tonnage for ${subject}` : `Cargoes for ${subject}`}
      extra={
        <Tooltip title="Also list the pairings that failed a check, with the reason. Worth turning on when a ship you expected is missing — that is a question with an answer.">
          <Space size={6}>
            <Switch size="small" checked={showRuledOut} onChange={setShowRuledOut} />
            <Typography.Text type="secondary">Show ruled out</Typography.Text>
          </Space>
        </Tooltip>
      }
    >
      {aside ? (
        // Each column scrolls on its own on a desktop, so the email stays in view while the
        // list is worked down. On a phone they stack and the drawer scrolls as one.
        <Row gutter={24}>
          <Col xs={24} lg={10} style={isMobile ? undefined : columnScroll}>
            {aside}
          </Col>
          <Col xs={24} lg={14} style={isMobile ? { marginTop: 24 } : columnScroll}>
            {list}
          </Col>
        </Row>
      ) : (
        list
      )}

      {/* Only the counterpart is ever reachable from a row, so only it is mounted: the cargo
          this list is scored for is the drawer underneath, already open. */}
      {side === 'position' && (
        <>
          <CargoDrawer
            cargoId={cargoDrawerId}
            onClose={() => setCargoDrawerId(undefined)}
            onEdit={(c) => {
              setEditingCargo(c);
              setCargoFormOpen(true);
            }}
          />
          <CargoForm
            open={cargoFormOpen}
            editing={editingCargo}
            onClose={() => setCargoFormOpen(false)}
            onDeleted={() => setCargoDrawerId(undefined)}
          />
        </>
      )}
      {side === 'cargo' && (
        <>
          <VesselDrawer
            vesselId={vesselDrawerId}
            onClose={() => setVesselDrawerId(undefined)}
            onEdit={(v) => {
              setEditingVessel(v);
              setVesselFormOpen(true);
            }}
          />
          <VesselForm
            open={vesselFormOpen}
            editing={editingVessel}
            onClose={() => setVesselFormOpen(false)}
            onDeleted={() => setVesselDrawerId(undefined)}
          />
        </>
      )}
    </Drawer>
  );
}
