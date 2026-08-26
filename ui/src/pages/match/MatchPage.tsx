import { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Card,
  Col,
  Empty,
  Row,
  Segmented,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  useMatchOverview,
  useMatchesForCargo,
  useMatchesForPosition,
  usePositions,
} from '../../api/hooks';
import { usePersistedState } from '../../components/usePersistedState';
import MatchList from './MatchList';
import { scoreColor } from './outcomes';
import { formatLaycan, formatPlace, formatQuantity } from '../cargoes/status';
import { formatFleetSize, formatOpenDates } from '../openFleet/status';

type Side = 'cargo' | 'position';

/**
 * Match: which of the ships on file suit which of the cargoes in hand.
 *
 * **Two directions, because the desk works in both.** A cargo arrives and wants tonnage;
 * that is the left-hand list. But most of the mail here is somebody else's tonnage asking
 * for work — "pls propose suitable cgoes for our below home tonnages" turns up weekly — and
 * answering that is the same scorer read the other way round.
 *
 * **Nothing on this screen is stored except what you decide.** The scores are computed on
 * each request from the live cargoes and the current positions, because a stored score goes
 * stale the moment either moves. What is kept is "not this ship for this cargo" — without
 * it the same fifteen ships come back every morning, four of them already offered.
 */
export default function MatchPage() {
  const [side, setSide] = usePersistedState<Side>('match.side', 'cargo');
  const [showRuledOut, setShowRuledOut] = usePersistedState('match.showRuledOut', false);
  const [cargoId, setCargoId] = useState<number>();
  const [positionId, setPositionId] = useState<number>();
  const [expanded, setExpanded] = useState<number[]>([]);

  const overview = useMatchOverview();
  // Only used by the by-vessel side; the fleet is small enough to offer in one dropdown.
  const fleet = usePositions({ current: true, size: 200, sort: 'reportedAt,desc' });

  const byCargo = useMatchesForCargo(side === 'cargo' ? cargoId : undefined, showRuledOut);
  const byPosition = useMatchesForPosition(side === 'position' ? positionId : undefined, showRuledOut);

  // Land on the cargo with the most unworked tonnage against it — the overview is already
  // sorted that way, so this is "open on where the day starts" rather than a guess.
  const summaries = overview.data ?? [];
  useEffect(() => {
    if (side === 'cargo' && cargoId == null && summaries.length > 0) {
      setCargoId(summaries[0].cargo.id);
    }
  }, [side, cargoId, summaries]);

  const toggleExpanded = (id: number) =>
    setExpanded((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  const controls = (
    <Space wrap>
      <Segmented<Side>
        value={side}
        onChange={(v) => {
          setSide(v);
          setExpanded([]);
        }}
        options={[
          { label: 'Tonnage for a cargo', value: 'cargo' },
          { label: 'Cargoes for a ship', value: 'position' },
        ]}
      />
      <Tooltip title="Also list the pairings that failed a check, with the reason. Worth turning on when a ship you expected is missing — that is a question with an answer.">
        <Space size={6}>
          <Switch size="small" checked={showRuledOut} onChange={setShowRuledOut} />
          <Typography.Text type="secondary">Show ruled out</Typography.Text>
        </Space>
      </Tooltip>
    </Space>
  );

  if (overview.isLoading) return <Spin style={{ display: 'block', margin: '64px auto' }} />;

  return (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        {controls}
      </Card>

      {side === 'cargo' ? (
        <Row gutter={16}>
          <Col xs={24} lg={9}>
            {summaries.length === 0 ? (
              <Empty description="No live cargoes. Add one on the Cargoes tab and the tonnage against it appears here." />
            ) : (
              <Space direction="vertical" size={8} style={{ width: '100%' }}>
                {summaries.map((s) => (
                  <Card
                    key={s.cargo.id}
                    size="small"
                    hoverable
                    onClick={() => {
                      setCargoId(s.cargo.id);
                      setExpanded([]);
                    }}
                    style={{
                      borderColor: s.cargo.id === cargoId ? '#1677ff' : undefined,
                      borderWidth: s.cargo.id === cargoId ? 2 : 1,
                    }}
                    styles={{ body: { padding: 12 } }}
                  >
                    <Space direction="vertical" size={2} style={{ width: '100%' }}>
                      <Space size={6} wrap>
                        <Typography.Text strong>{s.cargo.commodity}</Typography.Text>
                        <Typography.Text type="secondary">
                          {formatQuantity(
                            s.cargo.quantity,
                            s.cargo.quantityUnit,
                            s.cargo.quantityTolerance,
                          )}
                        </Typography.Text>
                      </Space>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {formatPlace(s.cargo.loadPortName, s.cargo.loadPortText, s.cargo.loadAreaCode)}
                        {' → '}
                        {formatPlace(
                          s.cargo.dischargePortName,
                          s.cargo.dischargePortText,
                          s.cargo.dischargeAreaCode,
                        )}
                        {' · '}
                        {formatLaycan(s.cargo.laycanFrom, s.cargo.laycanTo, s.cargo.laycanText)}
                      </Typography.Text>
                      <Space size={6} wrap>
                        {/* Unworked first: it is the number that says whether there is
                            anything to do here, which is not the same as how many ships fit. */}
                        <Tooltip title="Suitable ships nothing has been decided about yet">
                          <Badge
                            count={s.untouched}
                            showZero
                            style={{ backgroundColor: s.untouched > 0 ? '#1677ff' : '#bfbfbf' }}
                          />
                        </Tooltip>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          unworked of {s.suitable} suitable
                        </Typography.Text>
                        {s.suitable > 0 && (
                          <Tag color={undefined} style={{ color: scoreColor(s.bestScore) }}>
                            best {s.bestScore}
                          </Tag>
                        )}
                        {s.ruledOut > 0 && (
                          <Tooltip title="Pairings a check ruled out. Turn on 'show ruled out' to see why.">
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              {s.ruledOut} ruled out
                            </Typography.Text>
                          </Tooltip>
                        )}
                      </Space>
                    </Space>
                  </Card>
                ))}
              </Space>
            )}
          </Col>
          <Col xs={24} lg={15}>
            {cargoId == null ? (
              <Empty description="Pick a cargo" />
            ) : (
              <MatchList
                matches={byCargo.data ?? []}
                loading={byCargo.isLoading}
                side="cargo"
                expanded={expanded}
                onToggleExpanded={toggleExpanded}
              />
            )}
          </Col>
        </Row>
      ) : (
        <>
          <Card size="small" style={{ marginBottom: 16 }}>
            <Select
              showSearch
              style={{ width: '100%', maxWidth: 560 }}
              placeholder="Pick a ship from the open fleet"
              value={positionId}
              onChange={(v) => {
                setPositionId(v);
                setExpanded([]);
              }}
              optionFilterProp="label"
              options={(fleet.data?.content ?? []).map((p) => ({
                value: p.id,
                label: `${p.vessel.name} — ${formatFleetSize(p.vessel)} — open ${
                  p.openPortName ?? p.openPortText ?? p.openAreaName ?? '?'
                } ${formatOpenDates(p.openFrom, p.openTo, p.openText)}`,
              }))}
            />
            {(fleet.data?.content.length ?? 0) === 0 && (
              <Alert
                type="info"
                showIcon
                style={{ marginTop: 12 }}
                message="No live positions on file. Record one on the Open fleet tab and her cargoes appear here."
              />
            )}
          </Card>
          {positionId == null ? (
            <Empty description="Pick a ship" />
          ) : (
            <MatchList
              matches={byPosition.data ?? []}
              loading={byPosition.isLoading}
              side="position"
              expanded={expanded}
              onToggleExpanded={toggleExpanded}
            />
          )}
        </>
      )}
    </>
  );
}
