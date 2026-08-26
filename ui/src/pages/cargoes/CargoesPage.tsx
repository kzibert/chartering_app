import { useState } from 'react';
import { Button, Col, DatePicker, Form, Input, InputNumber, Row, Select, Space, Tag, Tooltip } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
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
import {
  CARGO_STATUS_META,
  CARGO_STATUS_OPTIONS,
  formatLaycan,
  formatPlace,
  formatQuantity,
} from './status';
import { LIVE_CARGO_STATUSES, type CargoFilter, type CargoResponse } from '../../api/types';

/**
 * Cargoes in hand.
 *
 * **The tab opens on live work, not on everything.** The API returns every status when none
 * is asked for, which is the right default for an API and the wrong one for this screen: a
 * desk that has worked a hundred cargoes wants the six it is still working, and the fixed
 * and failed ones are history to be searched for rather than scrolled past. The filter is
 * pre-set and visible, so nothing is hidden — clearing it shows the lot.
 */
export default function CargoesPage() {
  const [form] = Form.useForm();
  const [filters, setFilters] = usePersistedFilters<Partial<CargoFilter>>('cargoes', form);
  const tc = useTableControls({ size: 20 }, 'cargoes');
  const { setStatus } = useCargoMutations();

  const [selectedId, setSelectedId] = useState<number>();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<CargoResponse | null>(null);

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
    setFilters({
      ...(values as Partial<CargoFilter>),
      laycanFrom: values.laycanFrom ? (values.laycanFrom as dayjs.Dayjs).format('YYYY-MM-DD') : undefined,
      laycanTo: values.laycanTo ? (values.laycanTo as dayjs.Dayjs).format('YYYY-MM-DD') : undefined,
    });
    tc.resetPage();
  };

  const columns: ColumnsType<CargoResponse> = [
    {
      title: 'Cargo',
      dataIndex: 'commodity',
      fixed: 'left',
      render: (commodity: string, c) => (
        <Space size={4} wrap>
          {commodity}
          {c.fromMail && <Tag color="blue">mail</Tag>}
        </Space>
      ),
    },
    {
      title: 'Quantity',
      key: 'quantity',
      render: (_, c) => formatQuantity(c.quantity, c.quantityUnit, c.quantityTolerance),
    },
    {
      title: 'Load',
      key: 'load',
      render: (_, c) => formatPlace(c.loadPortName, c.loadPortText, c.loadAreaCode),
    },
    {
      title: 'Discharge',
      key: 'discharge',
      render: (_, c) => formatPlace(c.dischargePortName, c.dischargePortText, c.dischargeAreaCode),
    },
    {
      title: 'Laycan',
      key: 'laycan',
      sorter: true,
      sortOrder: tc.sortOrderFor('laycanFrom'),
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
      title: 'Status',
      dataIndex: 'status',
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
              style={{ width: 118 }}
              onChange={(next) => setStatus.mutate({ id: c.id, status: next })}
            />
          </Tooltip>
        </span>
      ),
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
              <Form.Item name="commodity" label="Commodity">
                <Input allowClear placeholder="wheat, hbi…" />
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item
                name="status"
                label="Status"
                tooltip="Empty means the live ones — open, quoted and firm. Pick statuses to see fixed, failed and the rest."
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
              <Form.Item name="loadAreaId" label="Load area">
                <TradeAreaSelect />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="dischargeAreaId" label="Discharge area">
                <TradeAreaSelect />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={5}>
              <Form.Item
                name="laycanFrom"
                label="Laycan from"
                tooltip="Matches any laycan overlapping the window, and always includes cargoes with no laycan on file."
              >
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={5}>
              <Form.Item name="laycanTo" label="Laycan to">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
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
              <Form.Item name="fromMail" label="Source" initialValue="">
                <Select
                  options={[
                    { value: '', label: 'Source: all' },
                    { value: false, label: 'Typed in the app' },
                    { value: true, label: 'Read from mail' },
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
        scroll={{ x: 1200 }}
        mobile={{
          title: (c) => (
            <Space size={4} wrap>
              {c.commodity}
              <Tag color={CARGO_STATUS_META[c.status].color}>{CARGO_STATUS_META[c.status].label}</Tag>
              {c.fromMail && <Tag color="blue">mail</Tag>}
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
            c.minDwt != null && { label: 'DWT min', value: c.minDwt.toLocaleString() },
            c.maxDwt != null && { label: 'DWT max', value: c.maxDwt.toLocaleString() },
            c.freightIdea != null && { label: 'Freight', value: c.freightIdea },
          ],
        }}
        mobileSort={[{ field: 'laycanFrom', label: 'Laycan' }, { field: 'id', label: 'Newest' }]}
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
      <CargoForm
        open={formOpen}
        editing={editing}
        onClose={() => setFormOpen(false)}
        onDeleted={() => setSelectedId(undefined)}
      />
    </>
  );
}
