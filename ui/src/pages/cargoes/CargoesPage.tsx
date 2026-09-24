import { useEffect, useState } from 'react';
import { Button, Col, DatePicker, Form, Input, InputNumber, Row, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { NodeIndexOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { useCargoes, useCargoMutations } from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import ResponsiveTable from '../../components/ResponsiveTable';
import FilterPanel, { countActiveFilters } from '../../components/FilterPanel';
import { usePersistedFilters } from '../../components/usePersistedState';
import TradeAreaSelect from '../../components/TradeAreaSelect';
import CargoDrawer from './CargoDrawer';
import CargoForm from './CargoForm';
import CargoMatchWindow from './CargoMatchWindow';
import {
  CARGO_STATUS_META,
  CARGO_STATUS_OPTIONS,
  formatLaycan,
  formatPlace,
  formatQuantity,
} from './status';
import {
  LIVE_CARGO_STATUSES,
  type CargoFilter,
  type CargoResponse,
  type SourceKind,
} from '../../api/types';

/** The filter fields held as a day, which the form wants as dayjs and the API as YYYY-MM-DD. */
const DATE_FILTERS = ['laycanFrom', 'laycanTo', 'sentSince'] as const;

/** "12 Sep 14:05", with the year only when it is not this one. */
function formatSent(iso?: string): string {
  if (!iso) return '—';
  const d = dayjs(iso);
  return d.format(d.year() === dayjs().year() ? 'D MMM HH:mm' : 'D MMM YYYY');
}

/**
 * Where a cargo came from, as one small tag.
 *
 * Typed carries none: a hand-entered cargo is the ordinary case and a tag on every row would
 * be noise. The other two are worth a glance — one arrived addressed to this desk, the other
 * was read off a page nobody sent us, which is the difference between a cargo somebody is
 * working with us and a cargo we happened to see.
 */
function sourceTag(kind: SourceKind) {
  if (kind === 'MAIL') return <Tag color="blue">mail</Tag>;
  if (kind === 'WEB') return <Tag color="cyan">web</Tag>;
  return null;
}

/** "Oceanic Brokers", or "Oceanic Brokers +2" when others sent it too. */
function formatSenders(c: CargoResponse): string {
  if (!c.lastSentBy) return c.sourceKind === 'MANUAL' ? 'typed in' : '—';
  const others = (c.senderCount ?? 1) - 1;
  return others > 0 ? `${c.lastSentBy} +${others}` : c.lastSentBy;
}

/**
 * Cargoes in hand.
 *
 * **The tab opens on live work, not on everything.** The API returns every status when none
 * is asked for, which is the right default for an API and the wrong one for this screen: a
 * desk that has worked a hundred cargoes wants the six it is still working, and the fixed
 * and failed ones are history to be searched for rather than scrolled past. The filter is
 * pre-set and visible, so nothing is hidden — clearing it shows the lot. Not workable is one
 * of the closed ones: it is out of this view and off Match for the same reason.
 *
 * **Every sort is the server's.** The list is paged, so a column sorted in the browser would
 * order twenty rows out of two hundred and look right. Each sortable column's `dataIndex` is
 * the name the API sorts on — `statusRank`, `loadPlace` and `lastSentAt` are derived on the
 * entity for exactly that — and the cell renders from the whole row.
 */
export default function CargoesPage() {
  const [form] = Form.useForm();
  const [filters, setFilters] = usePersistedFilters<Partial<CargoFilter>>('cargoes', form);
  // Newest arrival first by default: the question this list is opened with is what came in.
  const tc = useTableControls({ size: 20, sort: 'lastSentAt,desc' }, 'cargoes');
  const { setStatus } = useCargoMutations();

  const [selectedId, setSelectedId] = useState<number>();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<CargoResponse | null>(null);
  // The cargo whose full tonnage list is open, straight from its row - the same window the
  // drawer's own button opens, without reading the whole record on the way to it.
  const [matchingId, setMatchingId] = useState<number>();
  // An icon in the table, where a worded button was the widest thing on the row and pushed
  // itself off the right edge; the words stay on a phone's card, which has the room.
  const matchButton = (c: CargoResponse, iconOnly = false) =>
    LIVE_CARGO_STATUSES.includes(c.status) && (
      <Tooltip title="All matching vessels — every ship that suits this cargo, with the cargo and its email beside them">
        <Button
          size="small"
          type={iconOnly ? 'text' : 'default'}
          icon={<NodeIndexOutlined />}
          aria-label="All matching vessels"
          onClick={(e) => {
            e.stopPropagation();
            setMatchingId(c.id);
          }}
        >
          {iconOnly ? null : 'All matching vessels'}
        </Button>
      </Tooltip>
    );

  // The persisted filters hold dates as the strings the API takes, and a DatePicker handed a
  // string does not render it. Runs after usePersistedFilters' own restore, which it corrects.
  useEffect(() => {
    form.setFieldsValue(
      Object.fromEntries(
        DATE_FILTERS.map((k) => [k, filters[k] ? dayjs(filters[k]) : undefined]),
      ),
    );
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  // The live default applies only until the user says otherwise. `status` in the persisted
  // filters is an empty array once they have cleared it, which is a different thing from
  // never having touched it — hence the length check rather than a truthiness one.
  const status = filters.status?.length ? filters.status : LIVE_CARGO_STATUSES;

  const query = useCargoes({
    ...filters,
    status,
    page: tc.state.page,
    size: tc.state.size,
    sort: tc.state.sort,
  });

  const applyFilters = (values: Record<string, unknown>) => {
    const next = { ...(values as Partial<CargoFilter>) };
    for (const k of DATE_FILTERS) {
      next[k] = values[k] ? (values[k] as dayjs.Dayjs).format('YYYY-MM-DD') : undefined;
    }
    setFilters(next);
    tc.resetPage();
  };

  /** A column the server sorts, keyed by the field name it sorts on. */
  const sortable = (field: string) => ({
    dataIndex: field,
    sorter: true,
    sortOrder: tc.sortOrderFor(field),
  });

  const columns: ColumnsType<CargoResponse> = [
    {
      title: 'Cargo',
      ...sortable('commodity'),
      fixed: 'left',
      render: (commodity: string, c) => (
        <Space size={4} wrap>
          {commodity}
          {sourceTag(c.sourceKind)}
        </Space>
      ),
    },
    {
      title: 'Quantity',
      ...sortable('quantity'),
      render: (_, c) => formatQuantity(c.quantity, c.quantityUnit, c.quantityTolerance),
    },
    {
      title: 'Load',
      ...sortable('loadPlace'),
      render: (_, c) => formatPlace(c.loadPortName, c.loadPortText, c.loadAreaCode),
    },
    {
      title: 'Discharge',
      ...sortable('dischargePlace'),
      render: (_, c) => formatPlace(c.dischargePortName, c.dischargePortText, c.dischargeAreaCode),
    },
    {
      title: 'Laycan',
      ...sortable('laycanFrom'),
      render: (_, c) => formatLaycan(c.laycanFrom, c.laycanTo, c.laycanText),
    },
    {
      title: 'Wants',
      key: 'wants',
      render: (_, c) => {
        const bits: string[] = [];
        if (c.minDwt != null || c.maxDwt != null) {
          bits.push(`${c.minDwt?.toLocaleString() ?? '?'}–${c.maxDwt?.toLocaleString() ?? '?'} dwt`);
        }
        if (c.requiresGeared) bits.push('geared');
        if (c.requiresGrainFitted) bits.push('grain ftd');
        if (c.requiresImoFitted) bits.push('imo ftd');
        if (c.maxDraft != null) bits.push(`max ${c.maxDraft}m`);
        return bits.length ? bits.join(', ') : '—';
      },
    },
    {
      title: 'Last sent',
      ...sortable('lastSentAt'),
      render: (_, c) => (
        <Space direction="vertical" size={0}>
          {/* A typed cargo's date is when it was entered, and says so, rather than passing
              for a send that never happened. */}
          <Typography.Text type={c.lastSentBy ? undefined : 'secondary'}>
            {formatSent(c.lastSentAt)}
          </Typography.Text>
          <Tooltip title={c.senderCount && c.senderCount > 1 ? 'Open the cargo to see every sender and their emails' : undefined}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {formatSenders(c)}
            </Typography.Text>
          </Tooltip>
        </Space>
      ),
    },
    {
      title: 'Status',
      ...sortable('statusRank'),
      // Editable in the row: it is one field with its own endpoint, and moving a cargo
      // along is the most frequent write this screen carries. Stopping the click from
      // reaching the row keeps it from opening the drawer underneath.
      render: (_, c) => (
        <span onClick={(e) => e.stopPropagation()}>
          <Tooltip title={CARGO_STATUS_META[c.status].hint}>
            <Select
              size="small"
              value={c.status}
              options={CARGO_STATUS_OPTIONS}
              style={{ width: 128 }}
              onChange={(next) => setStatus.mutate({ id: c.id, status: next })}
            />
          </Tooltip>
        </span>
      ),
    },
    {
      title: '',
      key: 'match',
      width: 44,
      fixed: 'right',
      // Live cargoes only, as in the drawer: a fixed or declined one is not looking for tonnage.
      render: (_, c) => matchButton(c, true),
    },
  ];

  return (
    <>
      {/* The Form wraps the panel: on a phone the fields render into a drawer, which is a
          portal at the end of <body>, and only a Form above them still reaches them. */}
      <Form form={form} layout="vertical" onFinish={applyFilters}>
        <FilterPanel
          form={form}
          activeCount={countActiveFilters(filters)}
          onReset={() => {
            form.resetFields();
            applyFilters({});
          }}
          actions={
            <Button
              icon={<PlusOutlined />}
              onClick={() => {
                setEditing(null);
                setFormOpen(true);
              }}
            >
              New cargo
            </Button>
          }
        >
          <Row gutter={12}>
            <Col xs={24} md={6}>
              <Form.Item name="commodity" label="Cargo">
                <Input allowClear placeholder="wheat, hbi…" />
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item
                name="status"
                label="Status"
                tooltip="Empty means the live ones — open, quoted and firm. Pick statuses to see fixed, not workable and the rest."
              >
                <Select
                  mode="multiple"
                  allowClear
                  options={CARGO_STATUS_OPTIONS}
                  placeholder="Live (open, quoted, firm)"
                  maxTagCount="responsive"
                />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="loadPlace"
                label="Load"
                tooltip="Port on file, the words the email used, or the area's name or code."
              >
                <Input allowClear placeholder="port or area" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="dischargePlace" label="Discharge">
                <Input allowClear placeholder="port or area" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={6}>
              <Form.Item name="loadAreaId" label="Load area">
                <TradeAreaSelect />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="dischargeAreaId" label="Discharge area">
                <TradeAreaSelect />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="laycanFrom"
                label="Laycan from"
                tooltip="Matches any laycan overlapping the window, and always includes cargoes with no laycan on file."
              >
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="laycanTo" label="Laycan to">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={4}>
              <Form.Item name="minQuantity" label="Qty min">
                <InputNumber style={{ width: '100%' }} min={0} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="maxQuantity" label="Qty max">
                <InputNumber style={{ width: '100%' }} min={0} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="sentSince"
                label="Last sent since"
                tooltip="Cargoes whose newest email arrived on or after this day."
              >
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="sourceKind"
                label="Source"
                initialValue=""
                tooltip="Which door it came in through, not how good it is: a cargo typed out of a phone call is the best-checked row in the table, and one read off a board is the one nobody has looked at yet."
              >
                <Select
                  options={[
                    { value: '', label: 'Source: all' },
                    { value: 'MANUAL', label: 'Typed in the app' },
                    { value: 'MAIL', label: 'Read from the mailbox' },
                    { value: 'WEB', label: 'Read off an open board' },
                  ]}
                />
              </Form.Item>
            </Col>
          </Row>
        </FilterPanel>
      </Form>

      <ResponsiveTable<CargoResponse>
        rowKey="id"
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={query.data?.content ?? []}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
        scroll={{ x: 1340 }}
        mobile={{
          title: (c) => (
            <Space size={4} wrap>
              {c.commodity}
              <Tag color={CARGO_STATUS_META[c.status].color}>{CARGO_STATUS_META[c.status].label}</Tag>
              {sourceTag(c.sourceKind)}
            </Space>
          ),
          subtitle: (c) =>
            `${formatPlace(c.loadPortName, c.loadPortText, c.loadAreaCode)} → ${formatPlace(
              c.dischargePortName,
              c.dischargePortText,
              c.dischargeAreaCode,
            )}`,
          fields: (c) => [
            { label: 'Quantity', value: formatQuantity(c.quantity, c.quantityUnit, c.quantityTolerance) },
            { label: 'Laycan', value: formatLaycan(c.laycanFrom, c.laycanTo, c.laycanText) },
            { label: 'Last sent', value: formatSent(c.lastSentAt) },
            { label: 'Sent by', value: formatSenders(c) },
            c.minDwt != null && { label: 'DWT min', value: c.minDwt.toLocaleString() },
            c.maxDwt != null && { label: 'DWT max', value: c.maxDwt.toLocaleString() },
            c.freightIdea != null && { label: 'Freight', value: c.freightIdea },
          ],
          actions: (c) => matchButton(c) || null,
        }}
        mobileSort={[
          { field: 'lastSentAt', label: 'Last sent' },
          { field: 'laycanFrom', label: 'Laycan' },
          { field: 'commodity', label: 'Cargo' },
          { field: 'quantity', label: 'Quantity' },
          { field: 'loadPlace', label: 'Load' },
          { field: 'dischargePlace', label: 'Discharge' },
          { field: 'statusRank', label: 'Status' },
          { field: 'id', label: 'Newest' },
        ]}
        onRow={(c) => ({ onClick: () => setSelectedId(c.id), style: { cursor: 'pointer' } })}
      />

      <CargoDrawer
        cargoId={selectedId}
        onClose={() => setSelectedId(undefined)}
        onEdit={(c) => {
          setEditing(c);
          setFormOpen(true);
        }}
      />
      <CargoMatchWindow cargoId={matchingId} onClose={() => setMatchingId(undefined)} />
      <CargoForm
        open={formOpen}
        editing={editing}
        onClose={() => setFormOpen(false)}
        onDeleted={() => setSelectedId(undefined)}
      />
    </>
  );
}
