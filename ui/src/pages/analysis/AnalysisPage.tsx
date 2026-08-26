import { useState, type CSSProperties } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Form,
  Input,
  Row,
  Select,
  Space,
  Statistic,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CloudDownloadOutlined,
  EditOutlined,
  ExperimentOutlined,
  ImportOutlined,
  PlusOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import ResponsiveTable from '../../components/ResponsiveTable';
import FilterPanel, { countActiveFilters } from '../../components/FilterPanel';
import { usePersistedFilters } from '../../components/usePersistedState';
import { useTableControls } from '../../components/useTableControls';
import { useAnalysisMutations, useAnalysisSamples, useAnalysisStatus } from '../../analysis/store';
import CaptureModal from './CaptureModal';
import PasteSampleModal from './PasteSampleModal';
import SampleDrawer from './SampleDrawer';
import { LABELS, STATUSES, labelMeta, statusMeta } from './labels';
import type { AnalysisSampleFilter, AnalysisSampleResponse } from '../../api/analysis';

/**
 * One line, cut with an ellipsis where it runs out of column.
 *
 * A `Typography.Text ellipsis` inside a `Space` does not do this: the Space lays its items
 * out as flex children sized by their own content, and a nowrap line of 300 characters has
 * a content width of 300 characters. The cut has to be made by a block that has a width of
 * its own — the table cell's — which is what this is put on.
 */
const CLAMP: CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  display: 'block',
  minWidth: 0,
};

/**
 * The analysis workbench: incoming mail kept and labelled as finetuning data for a model
 * that reads cargo offers and vessel opening positions.
 *
 * <b>A local-only feature.</b> ANALYSIS_ENABLED is false on the hosted deployment, and this
 * page is reachable there only by typing the URL — which is why it renders an explanation
 * rather than an error when the API says the feature is off. The nav entry is already gone.
 *
 * The shape of the page follows the work: capture a batch, see what is still unlabelled,
 * open one, label it, move on. The counters at the top are not decoration — the useful
 * question at any moment is "how much is left", and after a session, "how much would export".
 */
export default function AnalysisPage() {
  const status = useAnalysisStatus();
  const [form] = Form.useForm();
  const [filters, setFilters] = usePersistedFilters<Partial<AnalysisSampleFilter>>(
    'analysis',
    form,
  );
  const tc = useTableControls({ size: 25 }, 'analysis');
  const [captureOpen, setCaptureOpen] = useState(false);
  const [pasteOpen, setPasteOpen] = useState(false);
  const [openId, setOpenId] = useState<number>();

  const enabled = status.data?.enabled === true;
  const query = useAnalysisSamples(
    { ...filters, page: tc.state.page, size: tc.state.size, sort: tc.state.sort },
    enabled,
  );
  const { exportJsonl } = useAnalysisMutations();
  const rows = query.data?.content ?? [];

  if (status.isLoading) return null;

  if (!enabled) {
    return (
      <Alert
        type="info"
        showIcon
        message="Email analysis is not enabled on this deployment"
        description={
          <>
            The analysis workbench keeps incoming mail as training data for a model that reads
            cargo offers and vessel positions. It is a local tool: a corpus built over months
            and worked through in long sittings, ending in a file handed to a training job
            elsewhere — none of which belongs on a hosted instance that sleeps when idle.
            <br />
            <br />
            Set <Typography.Text code>ANALYSIS_ENABLED=true</Typography.Text> and restart the
            api to run it here.
          </>
        }
      />
    );
  }

  const applyFilters = (values: Record<string, unknown>) => {
    const { range, ...rest } = values as { range?: [dayjs.Dayjs, dayjs.Dayjs] };
    setFilters({
      ...(rest as Partial<AnalysisSampleFilter>),
      receivedFrom: range?.[0]?.startOf('day').toISOString(),
      receivedTo: range?.[1]?.endOf('day').toISOString(),
    });
    tc.resetPage();
  };

  const columns: ColumnsType<AnalysisSampleResponse> = [
    {
      title: 'Received',
      dataIndex: 'receivedAt',
      key: 'receivedAt',
      width: 130,
      sorter: true,
      sortOrder: tc.sortOrderFor('receivedAt'),
      render: (_, r) => (
        <Typography.Text style={{ fontSize: 13 }}>
          {r.receivedAt ? dayjs(r.receivedAt).format('D MMM YY HH:mm') : '—'}
        </Typography.Text>
      ),
    },
    {
      title: 'From',
      key: 'from',
      width: 200,
      render: (_, r) => (
        <div style={{ minWidth: 0 }}>
          <div style={CLAMP}>
            <Typography.Text style={{ fontSize: 13 }}>
              {r.fromName || r.fromAddress || '—'}
            </Typography.Text>
          </div>
          {r.fromName && r.fromAddress && (
            <div style={CLAMP}>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {r.fromAddress}
              </Typography.Text>
            </div>
          )}
        </div>
      ),
    },
    {
      title: 'Email',
      key: 'email',
      render: (_, r) => (
        <div style={{ minWidth: 0 }}>
          <Tooltip title={r.subject || undefined}>
            <div style={CLAMP}>
              <Typography.Text strong style={{ fontSize: 13 }}>
                {r.subject || '(no subject)'}
              </Typography.Text>
            </div>
          </Tooltip>
          {r.snippet && (
            <div style={CLAMP}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {r.snippet}
              </Typography.Text>
            </div>
          )}
        </div>
      ),
    },
    {
      title: 'Label',
      key: 'label',
      width: 170,
      render: (_, r) => <SampleTags sample={r} />,
    },
    {
      title: '',
      key: 'actions',
      width: 100,
      render: (_, r) => (
        <Button size="small" icon={<EditOutlined />} onClick={() => setOpenId(r.id)}>
          Label
        </Button>
      ),
    },
  ];

  return (
    <>
      <CorpusHeader
        total={status.data?.totalSamples ?? 0}
        ready={status.data?.readySamples ?? 0}
        byLabel={status.data?.byLabel ?? {}}
        byStatus={status.data?.byStatus ?? {}}
        warnings={status.data?.warnings ?? []}
        exporting={exportJsonl.isPending}
        onExport={() => exportJsonl.mutate()}
      />

      {/* The Form wraps the panel: on a phone these fields render into a drawer portalled
          to the end of <body>, and only a Form above them in the tree still reaches them. */}
      <Form form={form} layout="vertical" onFinish={applyFilters}>
        <FilterPanel
          form={form}
          activeCount={countActiveFilters(filters)}
          onReset={() => {
            form.resetFields();
            setFilters({});
            tc.resetPage();
          }}
          actions={
            <>
              <Button
                type="primary"
                icon={<ImportOutlined />}
                onClick={() => setCaptureOpen(true)}
              >
                Capture from mailbox
              </Button>
              <Button icon={<PlusOutlined />} onClick={() => setPasteOpen(true)}>
                Paste an email
              </Button>
            </>
          }
        >
          <Row gutter={12}>
            <Col xs={24} md={7}>
              <Form.Item
                name="search"
                label="Contains"
                tooltip="Sender, subject, the email text and your own notes. The text is always searched here — you are looking for examples of a phrase, not for a message you half remember."
              >
                <Input allowClear placeholder="e.g. laycan, or abt 5,000" />
              </Form.Item>
            </Col>
            <Col xs={12} md={5}>
              <Form.Item name="label" label="Kind">
                <Select
                  allowClear
                  placeholder="Any"
                  options={LABELS.map((l) => ({ value: l.value, label: l.label, title: l.hint }))}
                />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="status" label="Review">
                <Select
                  allowClear
                  placeholder="Any"
                  options={STATUSES.map((s) => ({
                    value: s.value,
                    label: s.label,
                    title: s.hint,
                  }))}
                />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="source" label="Source">
                <Select
                  allowClear
                  placeholder="Any"
                  options={[
                    { value: 'MAILBOX', label: 'From the mailbox' },
                    { value: 'PASTED', label: 'Pasted in' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="range"
                label="Received between"
                tooltip="When the email arrived — not when it was captured"
              >
                <DatePicker.RangePicker style={{ width: '100%' }} allowEmpty={[true, true]} />
              </Form.Item>
            </Col>
          </Row>
        </FilterPanel>
      </Form>

      <ResponsiveTable<AnalysisSampleResponse>
        rowKey={(r) => r.id}
        size="small"
        // Content-sized columns are the wrong bargain here: one email with a 300-character
        // snippet takes the width it asks for, the table grows past the card, and the two
        // columns at the right-hand end — the labels and the button that sets them — are
        // the ones pushed off the edge. Fixed layout makes the widths above binding and
        // gives the slack to Email, which is the column that can be cut without loss.
        tableLayout="fixed"
        loading={query.isLoading}
        columns={columns}
        dataSource={rows}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
        onRow={(r) => ({ onClick: () => setOpenId(r.id), style: { cursor: 'pointer' } })}
        mobileSort={[{ field: 'receivedAt', label: 'Received' }]}
        mobile={{
          title: (r) => r.subject || '(no subject)',
          subtitle: (r) =>
            `${r.fromName || r.fromAddress || '—'}${
              r.receivedAt ? ` · ${dayjs(r.receivedAt).format('D MMM YY')}` : ''
            }`,
          fields: (r) => [
            { label: 'Label', value: <SampleTags sample={r} /> },
            r.snippet && { label: 'Text', value: r.snippet },
          ],
          actions: (r) => (
            <Button size="small" icon={<EditOutlined />} onClick={() => setOpenId(r.id)}>
              Label
            </Button>
          ),
        }}
      />

      <CaptureModal
        open={captureOpen}
        onClose={() => setCaptureOpen(false)}
        maxPerRun={status.data?.maxCapturePerRun ?? 500}
      />
      <PasteSampleModal
        open={pasteOpen}
        onClose={() => setPasteOpen(false)}
        onCreated={setOpenId}
      />
      <SampleDrawer
        sampleId={openId}
        templates={status.data?.annotationTemplates ?? {}}
        onClose={() => setOpenId(undefined)}
      />
    </>
  );
}

/**
 * How the corpus stands, and the one button that takes it out of here.
 *
 * The counters are the tab's real navigation: the work is "label what is still new" and
 * "check that what is ready is enough", and both are numbers rather than filters. The
 * per-label breakdown is next to them because a corpus that is nine parts cargo offers
 * trains a model that reads a position list as a cargo offer — that imbalance is invisible
 * in a list and obvious in a row of counts.
 */
function CorpusHeader({
  total,
  ready,
  byLabel,
  byStatus,
  warnings,
  exporting,
  onExport,
}: {
  total: number;
  ready: number;
  byLabel: Record<string, number>;
  byStatus: Record<string, number>;
  warnings: string[];
  exporting: boolean;
  onExport: () => void;
}) {
  return (
    <Card size="small" style={{ marginBottom: 16 }}>
      <Row gutter={[16, 12]} align="middle">
        <Col xs={12} md={5}>
          <Statistic
            title="Emails kept"
            value={total}
            prefix={<ExperimentOutlined />}
            valueStyle={{ fontSize: 22 }}
          />
        </Col>
        <Col xs={12} md={5}>
          <Statistic
            title={
              <Tooltip title="Labelled, annotated and marked ready — what an export would contain today">
                Ready to train on
              </Tooltip>
            }
            value={ready}
            valueStyle={{ fontSize: 22, color: ready > 0 ? '#389e0d' : undefined }}
          />
        </Col>
        <Col xs={24} md={14}>
          <Space direction="vertical" size={6} style={{ width: '100%' }}>
            <Space size={4} wrap>
              {LABELS.map((l) => {
                const n = byLabel[l.value] ?? 0;
                if (n === 0) return null;
                return (
                  <Tooltip key={l.value} title={l.hint}>
                    <Tag color={l.colour}>
                      {l.label}: {n}
                    </Tag>
                  </Tooltip>
                );
              })}
              {STATUSES.filter((s) => s.value !== 'READY').map((s) => {
                const n = byStatus[s.value] ?? 0;
                if (n === 0) return null;
                return (
                  <Tooltip key={s.value} title={s.hint}>
                    <Tag color={s.colour}>
                      {s.label}: {n}
                    </Tag>
                  </Tooltip>
                );
              })}
            </Space>
            <Button
              icon={<CloudDownloadOutlined />}
              onClick={onExport}
              loading={exporting}
              disabled={ready === 0}
            >
              Export {ready > 0 ? `${ready} ` : ''}as JSONL
            </Button>
          </Space>
        </Col>
      </Row>

      {warnings.length > 0 && (
        <Space direction="vertical" size={6} style={{ width: '100%', marginTop: 12 }}>
          {warnings.map((w) => (
            <Alert key={w} type="info" showIcon message={w} />
          ))}
        </Space>
      )}
    </Card>
  );
}

/** The two axes on one row, drawn the same way everywhere they appear. */
function SampleTags({ sample }: { sample: AnalysisSampleResponse }) {
  const l = labelMeta(sample.label);
  const s = statusMeta(sample.status);
  return (
    <Space size={4} wrap>
      <Tooltip title={l.hint}>
        <Tag color={l.colour}>{l.label}</Tag>
      </Tooltip>
      {sample.status !== 'NEW' && (
        <Tooltip title={s.hint}>
          <Tag color={s.colour}>{s.label}</Tag>
        </Tooltip>
      )}
      {sample.annotated && (
        <Tooltip title="An answer has been written for this email">
          <Tag color="cyan">annotated</Tag>
        </Tooltip>
      )}
    </Space>
  );
}
