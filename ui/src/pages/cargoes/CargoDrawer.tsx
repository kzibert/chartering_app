import { useEffect, useState } from 'react';
import { Button, Drawer, Empty, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { EditOutlined, MailOutlined, NodeIndexOutlined } from '@ant-design/icons';
import { useCargo, useCargoMutations, useMatchesForCargo } from '../../api/hooks';
import { useCargoSources } from '../../intake/store';
import OriginalEmail from '../../components/OriginalEmail';
import RecordHistory from '../../components/RecordHistory';
import CargoDetails from './CargoDetails';
import CargoSources from './CargoSources';
import CargoMatchWindow, { cargoEmailSources } from './CargoMatchWindow';
import MatchList from '../match/MatchList';
import VesselDrawer from '../vessels/VesselDrawer';
import VesselForm from '../vessels/VesselForm';
import { CARGO_STATUS_META, CARGO_STATUS_OPTIONS } from './status';
import { LIVE_CARGO_STATUSES } from '../../api/types';
import type { CargoResponse, CargoStatus, VesselResponse } from '../../api/types';

interface Props {
  cargoId?: number;
  onClose: () => void;
  onEdit: (cargo: CargoResponse) => void;
}

/** How many of the best ships the drawer shows before handing over to the full window. */
const TOP_MATCHES = 5;

/**
 * One cargo, read-only.
 *
 * The status dropdown is the exception, and it is here rather than only in the form for the
 * same reason the confirm tag sits on a vessel row: moving a cargo to Quoted or Fixed is the
 * single most frequent write on this screen, it changes one field, and it has its own
 * endpoint. Everything else that writes lives behind Edit.
 */
export default function CargoDrawer({ cargoId, onClose, onEdit }: Props) {
  const { data, isLoading } = useCargo(cargoId);
  const { setStatus } = useCargoMutations();
  // Every email this cargo arrived in. A merged cargo has several - that is what a merge is -
  // and the sources are not behind the parser switch, because they are part of the cargo
  // rather than part of the feature that created it.
  const { data: sources } = useCargoSources(cargoId);
  const [emailOpen, setEmailOpen] = useState(false);
  // Set when an arrival's own Read button opened the modal, so it opens on that email rather
  // than on the newest. Cleared by the header button, which means "the emails", not one.
  const [readingId, setReadingId] = useState<number>();
  const [matchOpen, setMatchOpen] = useState(false);
  const [expanded, setExpanded] = useState<number[]>([]);
  // The drawer is reused for whichever cargo is clicked next; its tonnage was not asked for.
  useEffect(() => {
    setMatchOpen(false);
    setExpanded([]);
  }, [cargoId]);

  // Live cargoes only, the rule matching itself reads by: a fixed or declined cargo is not
  // looking for tonnage, and a list of ships for it answers a question nobody is asking.
  const live = data != null && LIVE_CARGO_STATUSES.includes(data.status);
  // The same query, under the same key, as the full window with ruled-out off - opening it
  // after reading these five costs no second request.
  const matches = useMatchesForCargo(live ? cargoId : undefined, false);
  const [vesselDrawerId, setVesselDrawerId] = useState<number>();
  const [vesselFormOpen, setVesselFormOpen] = useState(false);
  const [editingVessel, setEditingVessel] = useState<VesselResponse | null>(null);

  // Absent rather than present and dead where the mailbox holds none of them.
  const readable = cargoEmailSources(data, sources);
  const hasSources = (sources?.length ?? 0) > 0;
  const allMatches = matches.data ?? [];

  return (
    <Drawer
      open={cargoId != null}
      onClose={onClose}
      width={640}
      title={data ? `${data.commodity}` : 'Cargo'}
      // Edit alone in the header. The status select and the email button used to sit here
      // too, and a drawer title gives way to its extras: with three controls beside it the
      // commodity was squeezed to nothing and the select drew over it.
      extra={
        data && (
          <Button icon={<EditOutlined />} onClick={() => onEdit(data)}>
            Edit
          </Button>
        )
      }
    >
      {isLoading && <Spin />}
      {!isLoading && !data && <Empty description="This cargo is no longer on file" />}
      {data && (
        <>
          <Space wrap style={{ marginBottom: 16 }}>
            <Tooltip title={CARGO_STATUS_META[data.status].hint} placement="bottom">
              <Select<CargoStatus>
                value={data.status}
                options={CARGO_STATUS_OPTIONS}
                style={{ width: 140 }}
                loading={setStatus.isPending}
                onChange={(status) => setStatus.mutate({ id: data.id, status })}
              />
            </Tooltip>
            {data.sourceKind === 'MAIL' && <Tag color="blue">from mail</Tag>}
            {data.sourceKind === 'WEB' && (
              <Tooltip
                title={
                  data.sourceFeedName
                    ? `Read off ${data.sourceFeedName} — a circular the firm posted publicly, not one addressed to this desk`
                    : 'Read off an open board — a circular the firm posted publicly, not one addressed to this desk'
                }
              >
                <Tag color="cyan">from the web</Tag>
              </Tooltip>
            )}
            {readable.length > 0 && (
              <Tooltip title="What a broker actually wrote. The only thing that settles whether a figure on this screen is right.">
                <Button
                  icon={<MailOutlined />}
                  onClick={() => {
                    setReadingId(undefined);
                    setEmailOpen(true);
                  }}
                >
                  Original email{readable.length > 1 ? `s (${readable.length})` : ''}
                </Button>
              </Tooltip>
            )}
          </Space>
          {data.statusNote && (
            <Typography.Paragraph type="secondary">{data.statusNote}</Typography.Paragraph>
          )}

          <CargoDetails cargo={data} hasSources={hasSources} />

          <CargoSources
            sources={sources ?? []}
            onRead={(id) => {
              setReadingId(id);
              setEmailOpen(true);
            }}
          />

          {/* The best few, after who sent it: once somebody has read what the cargo is and
              who is working it, the next question is which ship. Only a few, because this
              drawer is for reading one record - the whole list, with the cargo and its email
              kept beside it, is a window of its own. */}
          {live && (
            <>
              <Typography.Title level={5} style={{ marginTop: 24 }}>
                <Space wrap>
                  Matching vessels
                  {allMatches.length > TOP_MATCHES && (
                    <Tag>
                      best {TOP_MATCHES} of {allMatches.length}
                    </Tag>
                  )}
                </Space>
              </Typography.Title>
              {!matches.isLoading && allMatches.length === 0 ? (
                <Typography.Paragraph type="secondary">
                  No tonnage on file suits this cargo. The full list can show what was ruled
                  out, and why.
                </Typography.Paragraph>
              ) : (
                <MatchList
                  matches={allMatches.slice(0, TOP_MATCHES)}
                  loading={matches.isLoading}
                  side="cargo"
                  expanded={expanded}
                  onToggleExpanded={(id) =>
                    setExpanded((prev) =>
                      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
                    )
                  }
                  onOpenCargo={() => undefined}
                  onOpenVessel={setVesselDrawerId}
                />
              )}
              <Button
                icon={<NodeIndexOutlined />}
                style={{ marginTop: 8 }}
                onClick={() => setMatchOpen(true)}
              >
                All matching vessels
              </Button>
            </>
          )}

          {/* Every arrival, with a picker when there is more than one: a cargo three brokers
              sent is one record, and which of them said what is settled by reading them. */}
          <OriginalEmail
            sources={readable}
            initialMailMessageId={readingId}
            open={emailOpen}
            onClose={() => setEmailOpen(false)}
          />

          <RecordHistory entityType="cargo" entityId={data.id} />

          {/* The window can open a vessel, whose drawer can open her cargoes, so it is
              mounted only once asked for rather than a query ahead of anybody wanting it. */}
          <CargoMatchWindow
            cargoId={matchOpen ? data.id : undefined}
            onClose={() => setMatchOpen(false)}
          />
          {vesselDrawerId != null && (
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
        </>
      )}
    </Drawer>
  );
}
