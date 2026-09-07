import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Row,
  Segmented,
  Select,
  Space,
  Statistic,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CheckCircleOutlined,
  InboxOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import ResponsiveTable from '../../components/ResponsiveTable';
import { useTableControls } from '../../components/useTableControls';
import {
  useIntakeItems,
  useIntakeMutations,
  useIntakeStatus,
  useParsedEmails,
  useSweep,
} from '../../intake/store';
import IntakeItemDrawer from './IntakeItemDrawer';
import ParsedEmailDrawer from './ParsedEmailDrawer';
import { EMAIL_TYPES, KINDS, PARSE_STATUSES, kindMeta, parseStatusMeta } from './labels';
import type {
  IntakeItemKind,
  IntakeItemResponse,
  IntakeItemStatus,
  ParseStatus,
  ParsedEmailResponse,
} from '../../api/intake';

/**
 * Intake: what the model read out of the mail, and what it could not decide alone.
 *
 * <b>A local-only feature.</b> PARSER_ENABLED is false on the hosted deployment, and this
 * page is reachable there only by typing the URL — hence an explanation rather than an
 * error when the API says the feature is off. The nav entry is already gone.
 *
 * <b>Two views, because there are two questions.</b> The queue answers "what needs me": the
 * disagreements, the unknown hulls, the cargoes that look like duplicates. The log answers
 * the one a queue cannot — "was this morning's mail read at all, and what was made of it" —
 * which is how you notice a broker's daily list being classified as *neither* and quietly
 * producing nothing. Most days only the first is opened, which is why it is the default.
 *
 * The header is not decoration. Whether the model server is answering is the single most
 * likely thing to be wrong (it is a container on a desk, and desks get switched off), and
 * "how much mail is waiting" is what makes Parse now worth pressing or not.
 */
export default function IntakePage() {
  const status = useIntakeStatus();
  const [view, setView] = useState<'queue' | 'log'>('queue');
  const [kind, setKind] = useState<IntakeItemKind>();
  const [itemStatus, setItemStatus] = useState<IntakeItemStatus>('PENDING');
  const [parseStatus, setParseStatus] = useState<ParseStatus>();
  const [openItem, setOpenItem] = useState<number>();
  const [openParsed, setOpenParsed] = useState<number>();

  const enabled = status.data?.enabled === true;
  const tc = useTableControls({ size: 25 }, 'intake');
  const logTc = useTableControls({ size: 25 }, 'intake-log');

  const items = useIntakeItems(
    { kind, status: itemStatus, page: tc.state.page, size: tc.state.size },
    enabled && view === 'queue',
  );
  const parsed = useParsedEmails(
    { status: parseStatus, page: logTc.state.page, size: logTc.state.size },
    enabled && view === 'log',
  );
  const sweep = useSweep(enabled);
  const { startSweep } = useIntakeMutations();

  if (status.isLoading) return null;

  if (!enabled) {
    return (
      <Alert
        type="info"
        showIcon
        message="The email parser is not enabled on this deployment"
        description={
          <>
            Intake reads incoming mail with a local model and files what it finds — open
            positions onto the Open fleet tab, cargoes onto the Cargoes tab, so Match has both
            sides to work with. The model is not part of this application: it is llama.cpp
            serving a finetuned model on a machine with a GPU, which a hosted instance has
            neither of nor a route to.
            <br />
            <br />
            Start it in the <Typography.Text code>chartering-ml</Typography.Text> project with{' '}
            <Typography.Text code>make serve-docker</Typography.Text>, then set{' '}
            <Typography.Text code>PARSER_ENABLED=true</Typography.Text> and restart the api.
          </>
        }
      />
    );
  }

  const running = sweep.data?.running === true || status.data?.running === true;

  return (
    <>
      <IntakeHeader
        pending={status.data?.pendingItems ?? 0}
        unparsed={status.data?.unparsed ?? 0}
        parsedTotal={status.data?.parsedTotal ?? 0}
        failedTotal={status.data?.failedTotal ?? 0}
        reachable={status.data?.reachable === true}
        modelUrl={status.data?.modelUrl}
        intervalMinutes={status.data?.sweepIntervalMinutes ?? 0}
        maxAgeDays={status.data?.sweepMaxAgeDays ?? 0}
        nextSweepAt={status.data?.nextSweepAt}
        lastSweepAt={status.data?.lastSweepAt}
        lastSummary={sweep.data?.message ?? status.data?.lastSweepSummary}
        warnings={status.data?.warnings ?? []}
        running={running}
        onSweep={() => startSweep.mutate()}
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap size={12}>
          <Segmented
            value={view}
            onChange={(v) => setView(v as 'queue' | 'log')}
            options={[
              { label: 'Needs review', value: 'queue' },
              { label: 'What was read', value: 'log' },
            ]}
          />
          {view === 'queue' ? (
            <>
              <Select
                allowClear
                placeholder="Any kind"
                style={{ minWidth: 190 }}
                value={kind}
                onChange={(v) => {
                  setKind(v);
                  tc.resetPage();
                }}
                options={KINDS.map((k) => ({ value: k.value, label: k.label, title: k.hint }))}
              />
              <Select
                style={{ minWidth: 150 }}
                value={itemStatus}
                onChange={(v) => {
                  setItemStatus(v);
                  tc.resetPage();
                }}
                options={[
                  { value: 'PENDING', label: 'Still waiting' },
                  { value: 'ACCEPTED', label: 'Answered' },
                  { value: 'REJECTED', label: 'Discarded' },
                ]}
              />
            </>
          ) : (
            <Select
              allowClear
              placeholder="Any outcome"
              style={{ minWidth: 170 }}
              value={parseStatus}
              onChange={(v) => {
                setParseStatus(v);
                logTc.resetPage();
              }}
              options={PARSE_STATUSES.map((s) => ({
                value: s.value,
                label: s.label,
                title: s.hint,
              }))}
            />
          )}
        </Space>
      </Card>

      {view === 'queue' ? (
        <QueueTable
          rows={items.data?.content ?? []}
          total={items.data?.totalElements ?? 0}
          loading={items.isLoading}
          tc={tc}
          onOpen={setOpenItem}
        />
      ) : (
        <LogTable
          rows={parsed.data?.content ?? []}
          total={parsed.data?.totalElements ?? 0}
          loading={parsed.isLoading}
          tc={logTc}
          onOpen={setOpenParsed}
        />
      )}

      <IntakeItemDrawer itemId={openItem} onClose={() => setOpenItem(undefined)} />
      <ParsedEmailDrawer parsedId={openParsed} onClose={() => setOpenParsed(undefined)} />
    </>
  );
}

/**
 * How the reading is going, and the one button that starts it.
 *
 * `Parse now` is here rather than beside the filters because it is the tab's only action and
 * it is what somebody opens the page to press when the timer is off — which is a supported
 * way to run this, not a broken one.
 */
function IntakeHeader({
  pending,
  unparsed,
  parsedTotal,
  failedTotal,
  reachable,
  modelUrl,
  intervalMinutes,
  maxAgeDays,
  nextSweepAt,
  lastSweepAt,
  lastSummary,
  warnings,
  running,
  onSweep,
}: {
  pending: number;
  unparsed: number;
  parsedTotal: number;
  failedTotal: number;
  reachable: boolean;
  modelUrl?: string;
  intervalMinutes: number;
  maxAgeDays: number;
  nextSweepAt?: string;
  lastSweepAt?: string;
  lastSummary?: string;
  warnings: string[];
  running: boolean;
  onSweep: () => void;
}) {
  const schedule =
    intervalMinutes === 0
      ? 'Timer off — Parse now is the only way in'
      : `Every ${intervalMinutes} min` +
        (nextSweepAt ? `, next about ${dayjs(nextSweepAt).format('HH:mm')}` : '');

  return (
    <Card size="small" style={{ marginBottom: 16 }}>
      <Row gutter={[16, 12]} align="middle">
        <Col xs={12} md={5}>
          <Statistic
            title={
              <Tooltip title="Readings the parser would not apply on its own — a particular that disagrees, a hull nobody has heard of, a cargo that looks like one already in hand">
                Needs review
              </Tooltip>
            }
            value={pending}
            prefix={<InboxOutlined />}
            valueStyle={{ fontSize: 22, color: pending > 0 ? '#d46b08' : undefined }}
          />
        </Col>
        <Col xs={12} md={5}>
          <Statistic
            title={
              <Tooltip title="Synced mail the model has not been given yet, inside the age limit. The next sweep takes them newest first.">
                Waiting to be read
              </Tooltip>
            }
            value={unparsed}
            valueStyle={{ fontSize: 22 }}
          />
        </Col>
        <Col xs={24} md={14}>
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            <Space size={4} wrap>
              <Tooltip
                title={
                  reachable
                    ? `Answering at ${modelUrl}`
                    : `Not answering at ${modelUrl}. Start it in the chartering-ml project: make serve-docker`
                }
              >
                <Tag color={reachable ? 'green' : 'red'}>
                  {reachable ? 'Model server up' : 'Model server down'}
                </Tag>
              </Tooltip>
              <Tag color="default">{schedule}</Tag>
            <Tooltip title="Mail older than this is left alone. A position list from last year is not information — the ship sailed. Change it on the Settings tab; 0 removes the limit.">
              <Tag color="default">
                {maxAgeDays === 0 ? 'No age limit' : `Last ${maxAgeDays} days`}
              </Tag>
            </Tooltip>
              {parsedTotal > 0 && <Tag color="blue">{parsedTotal} read</Tag>}
              {failedTotal > 0 && <Tag color="red">{failedTotal} failed</Tag>}
            </Space>
            <Space wrap>
              <Button
                type="primary"
                icon={running ? <ReloadOutlined spin /> : <ThunderboltOutlined />}
                onClick={onSweep}
                loading={running}
                disabled={running || !reachable}
              >
                {running ? 'Reading…' : 'Parse now'}
              </Button>
              {lastSummary && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {lastSummary}
                  {lastSweepAt ? ` · last run ${dayjs(lastSweepAt).format('D MMM HH:mm')}` : ''}
                </Typography.Text>
              )}
            </Space>
          </Space>
        </Col>
      </Row>

      {warnings.length > 0 && (
        <Space direction="vertical" size={6} style={{ width: '100%', marginTop: 12 }}>
          {warnings.map((w) => (
            <Alert key={w} type="warning" showIcon message={w} />
          ))}
        </Space>
      )}
    </Card>
  );
}

function QueueTable({
  rows,
  total,
  loading,
  tc,
  onOpen,
}: {
  rows: IntakeItemResponse[];
  total: number;
  loading: boolean;
  tc: ReturnType<typeof useTableControls>;
  onOpen: (id: number) => void;
}) {
  const columns: ColumnsType<IntakeItemResponse> = [
    {
      title: 'Question',
      key: 'kind',
      width: 170,
      render: (_, r) => {
        const meta = kindMeta(r.kind);
        return (
          <Tooltip title={meta.hint}>
            <Tag color={meta.colour}>{meta.label}</Tag>
          </Tooltip>
        );
      },
    },
    {
      title: 'About',
      key: 'subject',
      render: (_, r) => (
        <div style={{ minWidth: 0 }}>
          <Typography.Text strong style={{ fontSize: 13 }}>
            {r.subjectLabel || '—'}
          </Typography.Text>
          {r.summary && (
            <div>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {r.summary}
              </Typography.Text>
            </div>
          )}
        </div>
      ),
    },
    {
      title: 'From the email',
      key: 'mail',
      width: 260,
      render: (_, r) => (
        <div style={{ minWidth: 0 }}>
          <Typography.Text style={{ fontSize: 13 }}>
            {r.fromName || r.fromAddress || '—'}
          </Typography.Text>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {r.mailSubject || '(no subject)'}
              {r.receivedAt ? ` · ${dayjs(r.receivedAt).format('D MMM HH:mm')}` : ''}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 100,
      render: (_, r) => (
        <Button size="small" onClick={() => onOpen(r.id)}>
          {r.status === 'PENDING' ? 'Review' : 'Open'}
        </Button>
      ),
    },
  ];

  return (
    <ResponsiveTable<IntakeItemResponse>
      rowKey={(r) => r.id}
      size="small"
      tableLayout="fixed"
      loading={loading}
      columns={columns}
      dataSource={rows}
      pagination={tc.pagination(total)}
      onChange={tc.onChange}
      onRow={(r) => ({ onClick: () => onOpen(r.id), style: { cursor: 'pointer' } })}
      locale={{
        emptyText: 'Nothing waiting. Positions and cargoes the parser was sure about have already been filed.',
      }}
      mobile={{
        title: (r) => r.subjectLabel || '—',
        subtitle: (r) => kindMeta(r.kind).label,
        fields: (r) => [
          r.summary && { label: 'Why', value: r.summary },
          { label: 'From', value: r.fromName || r.fromAddress || '—' },
        ],
        actions: (r) => (
          <Button size="small" onClick={() => onOpen(r.id)}>
            {r.status === 'PENDING' ? 'Review' : 'Open'}
          </Button>
        ),
      }}
    />
  );
}

function LogTable({
  rows,
  total,
  loading,
  tc,
  onOpen,
}: {
  rows: ParsedEmailResponse[];
  total: number;
  loading: boolean;
  tc: ReturnType<typeof useTableControls>;
  onOpen: (id: number) => void;
}) {
  const columns: ColumnsType<ParsedEmailResponse> = [
    {
      title: 'Read',
      dataIndex: 'parsedAt',
      key: 'parsedAt',
      width: 130,
      render: (_, r) => (
        <Typography.Text style={{ fontSize: 13 }}>
          {r.parsedAt ? dayjs(r.parsedAt).format('D MMM HH:mm') : '—'}
        </Typography.Text>
      ),
    },
    {
      title: 'Email',
      key: 'subject',
      render: (_, r) => (
        <div style={{ minWidth: 0 }}>
          <Typography.Text strong style={{ fontSize: 13 }}>
            {r.subject || '(no subject)'}
          </Typography.Text>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
              {r.fromName || r.fromAddress || '—'}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: 'Read as',
      key: 'type',
      width: 130,
      render: (_, r) => {
        const meta = r.emailType ? EMAIL_TYPES[r.emailType] : undefined;
        return meta ? <Tag color={meta.colour}>{meta.label}</Tag> : <Tag>{r.emailType || '—'}</Tag>;
      },
    },
    {
      title: 'Filed',
      key: 'applied',
      width: 210,
      render: (_, r) => (
        <Space size={4} wrap>
          {r.positionsApplied > 0 && <Tag color="green">{r.positionsApplied} position(s)</Tag>}
          {r.cargoesApplied > 0 && <Tag color="blue">{r.cargoesApplied} cargo(es)</Tag>}
          {r.itemsRaised > 0 && <Tag color="orange">{r.itemsRaised} to review</Tag>}
          {r.positionsApplied + r.cargoesApplied + r.itemsRaised === 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              nothing
            </Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: 'Outcome',
      key: 'status',
      width: 110,
      render: (_, r) => {
        const meta = parseStatusMeta(r.status);
        return (
          <Tooltip title={r.error || meta.hint}>
            <Tag color={meta.colour}>{meta.label}</Tag>
          </Tooltip>
        );
      },
    },
  ];

  return (
    <ResponsiveTable<ParsedEmailResponse>
      rowKey={(r) => r.id}
      size="small"
      tableLayout="fixed"
      loading={loading}
      columns={columns}
      dataSource={rows}
      pagination={tc.pagination(total)}
      onChange={tc.onChange}
      onRow={(r) => ({ onClick: () => onOpen(r.id), style: { cursor: 'pointer' } })}
      locale={{ emptyText: 'Nothing has been read yet. Press Parse now.' }}
      mobile={{
        title: (r) => r.subject || '(no subject)',
        subtitle: (r) =>
          `${r.fromName || r.fromAddress || '—'}${
            r.parsedAt ? ` · ${dayjs(r.parsedAt).format('D MMM HH:mm')}` : ''
          }`,
        fields: (r) => [
          { label: 'Read as', value: r.emailType || '—' },
          {
            label: 'Filed',
            value: `${r.positionsApplied} position(s), ${r.cargoesApplied} cargo(es), ${r.itemsRaised} to review`,
          },
          r.error && { label: 'Error', value: r.error },
        ],
        actions: (r) => (
          <Button size="small" icon={<CheckCircleOutlined />} onClick={() => onOpen(r.id)}>
            Open
          </Button>
        ),
      }}
    />
  );
}
