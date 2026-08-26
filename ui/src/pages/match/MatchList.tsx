import { Button, Card, Dropdown, Empty, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { DownOutlined } from '@ant-design/icons';
import { useMatchMutations } from '../../api/hooks';
import { formatFleetSize, formatOpenDates, staleness } from '../openFleet/status';
import { formatLaycan, formatPlace, formatQuantity } from '../cargoes/status';
import MatchChecks, { CheckSummary } from './MatchChecks';
import { OUTCOME_META, OUTCOME_OPTIONS, scoreColor } from './outcomes';
import type { MatchOutcome, MatchResponse } from '../../api/types';

interface Props {
  matches: MatchResponse[];
  loading?: boolean;
  /** 'cargo' lists ships against one cargo; 'position' lists cargoes against one ship. */
  side: 'cargo' | 'position';
  /** Ids of the rows whose reasons are open. */
  expanded: number[];
  onToggleExpanded: (id: number) => void;
}

/**
 * The scored pairings, best first.
 *
 * Each row leads with the score and the ship (or the cargo), and carries a one-line summary
 * of what failed or could not be answered. The full reasons open on click rather than
 * always: a broker scanning fifteen ships wants fifteen lines, and the one they stop on is
 * the one they want the whole argument for.
 *
 * The outcome control writes immediately. It is one field with its own endpoint, and it is
 * the only thing on this screen that writes anything at all — everything else is computed
 * fresh on each request.
 */
export default function MatchList({ matches, loading, side, expanded, onToggleExpanded }: Props) {
  const { decide, clear } = useMatchMutations();

  if (loading) return <Spin style={{ display: 'block', margin: '48px auto' }} />;
  if (matches.length === 0) {
    return (
      <Empty
        description={
          side === 'cargo'
            ? 'No tonnage on file suits this cargo. Turn on "show ruled out" to see what was excluded and why — that list is usually more useful than this message.'
            : 'No live cargo suits this ship.'
        }
      />
    );
  }

  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      {matches.map((m) => {
        const key = m.position?.id ?? m.cargo.id;
        const isOpen = expanded.includes(key);
        const outcome = m.outcome ? OUTCOME_META[m.outcome] : undefined;
        const dimmed = m.ruledOut || (outcome?.closes ?? false);

        return (
          <Card
            key={key}
            size="small"
            style={{ opacity: dimmed ? 0.62 : 1 }}
            styles={{ body: { padding: 12 } }}
          >
            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', flexWrap: 'wrap' }}>
              {/* The score, sized to be read across a room of fifteen rows. Ruled-out pairs
                  show a dash instead: their score is arithmetic on criteria that stopped
                  mattering the moment one of them came back FAIL. */}
              <Tooltip title="The share of what the cargo asked for that this ship demonstrably meets. Unanswered checks cost points — they are not counted as passes.">
                <div style={{ minWidth: 52, textAlign: 'center' }}>
                  <Typography.Text
                    strong
                    style={{ fontSize: 22, color: m.ruledOut ? '#bfbfbf' : scoreColor(m.score) }}
                  >
                    {m.ruledOut ? '—' : m.score}
                  </Typography.Text>
                </div>
              </Tooltip>

              <div style={{ flex: '1 1 320px', minWidth: 0 }}>
                <Space size={6} wrap>
                  <Typography.Text strong>
                    {side === 'cargo' ? m.position?.vessel.name ?? '—' : m.cargo.commodity}
                  </Typography.Text>
                  {outcome && <Tag color={outcome.color}>{outcome.label}</Tag>}
                  {m.ruledOut && <Tag color="red">ruled out</Tag>}
                </Space>

                <div>
                  <Typography.Text type="secondary">
                    {side === 'cargo'
                      ? m.position
                        ? `${formatFleetSize(m.position.vessel)} · open ${
                            m.position.openPortName ??
                            m.position.openPortText ??
                            m.position.openAreaName ??
                            '?'
                          } ${formatOpenDates(
                            m.position.openFrom,
                            m.position.openTo,
                            m.position.openText,
                          )} · reported ${staleness(m.position.ageDays).text}`
                        : 'no live position'
                      : `${formatQuantity(
                          m.cargo.quantity,
                          m.cargo.quantityUnit,
                          m.cargo.quantityTolerance,
                        )} · ${formatPlace(
                          m.cargo.loadPortName,
                          m.cargo.loadPortText,
                          m.cargo.loadAreaCode,
                        )} → ${formatPlace(
                          m.cargo.dischargePortName,
                          m.cargo.dischargePortText,
                          m.cargo.dischargeAreaCode,
                        )} · ${formatLaycan(m.cargo.laycanFrom, m.cargo.laycanTo, m.cargo.laycanText)}`}
                  </Typography.Text>
                </div>

                <div style={{ marginTop: 4 }}>
                  <CheckSummary checks={m.checks} />
                </div>

                {m.earliestArrival && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    Could present {m.earliestArrival}
                    {m.ballastDays != null && m.ballastDays > 0 && ` · ${m.ballastDays}d ballast`}
                  </Typography.Text>
                )}
              </div>

              <Space size={4}>
                <Button size="small" onClick={() => onToggleExpanded(key)}>
                  {isOpen ? 'Hide reasons' : 'Why'}
                </Button>
                <Dropdown
                  disabled={!m.position}
                  menu={{
                    items: [
                      ...OUTCOME_OPTIONS.map((o) => ({
                        key: o.value,
                        label: (
                          <Tooltip
                            title={OUTCOME_META[o.value as MatchOutcome].hint}
                            placement="left"
                          >
                            <span>{o.label}</span>
                          </Tooltip>
                        ),
                      })),
                      ...(m.outcome
                        ? [
                            { type: 'divider' as const, key: 'div' },
                            { key: '__clear', label: 'Forget this decision' },
                          ]
                        : []),
                    ],
                    onClick: ({ key: chosen }) => {
                      if (!m.position) return;
                      const vesselId = m.position.vessel.id;
                      if (chosen === '__clear') {
                        clear.mutate({ cargoId: m.cargo.id, vesselId });
                        return;
                      }
                      decide.mutate({
                        cargoId: m.cargo.id,
                        vesselId,
                        body: {
                          outcome: chosen as MatchOutcome,
                          vesselPositionId: m.position.id,
                        },
                      });
                    },
                  }}
                >
                  <Button size="small" type={m.outcome ? 'default' : 'primary'}>
                    {m.outcome ? OUTCOME_META[m.outcome].label : 'Record'} <DownOutlined />
                  </Button>
                </Dropdown>
              </Space>
            </div>

            {isOpen && (
              <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid rgba(5,5,5,0.06)' }}>
                <MatchChecks checks={m.checks} />
                {m.outcomeNote && (
                  <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0 }}>
                    {m.outcomeNote}
                  </Typography.Paragraph>
                )}
              </div>
            )}
          </Card>
        );
      })}
    </Space>
  );
}
