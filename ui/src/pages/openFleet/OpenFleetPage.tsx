import { useState } from 'react';
import { Button, Checkbox, Col, DatePicker, Form, Input, InputNumber, Popconfirm, Row, Select, Space, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { usePositions, usePositionMutations } from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import ResponsiveTable from '../../components/ResponsiveTable';
import FilterPanel, { countActiveFilters } from '../../components/FilterPanel';
import { usePersistedFilters } from '../../components/usePersistedState';
import TradeAreaSelect from '../../components/TradeAreaSelect';
import CompanySelect from '../../components/CompanySelect';
import PositionForm from './PositionForm';
import VesselDrawer from '../vessels/VesselDrawer';
import VesselForm from '../vessels/VesselForm';
import {
  POSITION_STATUS_META,
  POSITION_STATUS_OPTIONS,
  formatFleetSize,
  formatOpenDates,
  staleness,
} from './status';
import type { PositionFilter, VesselPositionResponse, VesselResponse } from '../../api/types';

/**
 * Open fleet: where the tonnage we have been told about is free, and when.
 *
 * **One row per vessel, not one per report.** The table shows the newest live reading for
 * each hull, because a fleet list with the same ship on it twice is a fleet list nobody can
 * count. The readings it hides are not lost — untick "Newest per vessel" and every position
 * ever reported comes back, superseded ones included, which is where two brokers disagreeing
 * about the same ship is visible.
 *
 * The age column is doing real work. A position list is a weekly document and "SPOT AT
 * MARMARA" was true on Monday and a lie by Friday, so a reading's age is as much a part of
 * it as the dates it carries.
 */
export default function OpenFleetPage() {
  const [form] = Form.useForm();
  const [filters, setFilters] = usePersistedFilters<Partial<PositionFilter>>('openFleet', form);
  const tc = useTableControls({ size: 25, sort: 'reportedAt,desc' }, 'openFleet');
  const { setStatus, remove } = usePositionMutations();

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<VesselPositionResponse | null>(null);

  // A row is a reading, but the question it usually raises is about the ship carrying it —
  // what she is, who owns her, where else she has been reported. Clicking the row opens her
  // record, the same drawer the Vessels tab opens, so that question is answered here rather
  // than by searching the name out in another tab. The controls that write to the *position*
  // — the status select, edit and delete — stop the click, so they still act on the reading.
  const [vesselId, setVesselId] = useState<number>();
  const [vesselFormOpen, setVesselFormOpen] = useState(false);
  const [editingVessel, setEditingVessel] = useState<VesselResponse | null>(null);

  // Default on: the tab is called Open fleet, and that is what it means. The checkbox is on
  // screen rather than implied, so the narrowing is never invisible.
  const current = filters.current ?? true;

  const query = usePositions({
    ...filters,
    current,
    page: tc.state.page,
    size: tc.state.size,
    sort: tc.state.sort,
  });

  const applyFilters = (values: Record<string, unknown>) => {
    setFilters({
      ...(values as Partial<PositionFilter>),
      openFrom: values.openFrom ? (values.openFrom as dayjs.Dayjs).format('YYYY-MM-DD') : undefined,
      openTo: values.openTo ? (values.openTo as dayjs.Dayjs).format('YYYY-MM-DD') : undefined,
    });
    tc.resetPage();
  };

  const openForm = (p: VesselPositionResponse | null) => {
    setEditing(p);
    setFormOpen(true);
  };

  const columns: ColumnsType<VesselPositionResponse> = [
    {
      title: 'Vessel',
      key: 'vessel',
      fixed: 'left',
      render: (_, p) => (
        <Space direction="vertical" size={0}>
          <Space size={4} wrap>
            {p.vessel.name}
            {p.vessel.banned && <Tag color="red">banned</Tag>}
          </Space>
          {(p.vessel.exNames?.length ?? 0) > 0 && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              ex {p.vessel.exNames!.map((e) => e.name).join(', ')}
            </Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: 'Size',
      key: 'size',
      // The figure the size filter actually compares — DWCC where it exists, DWT where it
      // does not — with the label saying which, so a 6,100 next to a 6,354 is explicable.
      render: (_, p) => formatFleetSize(p.vessel),
    },
    {
      title: 'Gear',
      key: 'gear',
      render: (_, p) =>
        p.vessel.geared == null ? (
          <Typography.Text type="secondary">—</Typography.Text>
        ) : (
          <Tooltip title={p.vessel.gearDescription}>
            <Tag color={p.vessel.geared ? 'blue' : 'default'}>
              {p.vessel.geared ? 'geared' : 'gearless'}
            </Tag>
          </Tooltip>
        ),
    },
    {
      title: 'Open',
      key: 'open',
      render: (_, p) => (
        <Space direction="vertical" size={0}>
          <span>{p.openPortName ?? p.openPortText ?? p.openAreaName ?? '—'}</span>
          {p.openAreaCode && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {p.openAreaCode}
            </Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: 'Dates',
      key: 'dates',
      sorter: true,
      sortOrder: tc.sortOrderFor('openFrom'),
      render: (_, p) => formatOpenDates(p.openFrom, p.openTo, p.openText),
    },
    { title: 'Last cargo', dataIndex: 'lastCargo', render: (v?: string) => v ?? '—' },
    {
      title: 'Reported',
      key: 'reported',
      sorter: true,
      sortOrder: tc.sortOrderFor('reportedAt'),
      render: (_, p) => {
        const s = staleness(p.ageDays);
        return (
          <Space direction="vertical" size={0}>
            <Typography.Text type={s.color === 'red' ? 'danger' : undefined} style={{ color: s.color === 'orange' ? '#d46b08' : undefined }}>
              {s.text}
            </Typography.Text>
            {p.reportedByCompanyName && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {p.reportedByCompanyName}
              </Typography.Text>
            )}
          </Space>
        );
      },
    },
    {
      title: 'Status',
      key: 'status',
      render: (_, p) => (
        <span onClick={(e) => e.stopPropagation()}>
          <Tooltip title={POSITION_STATUS_META[p.status].hint}>
            <Select
              size="small"
              value={p.status}
              options={POSITION_STATUS_OPTIONS}
              style={{ width: 124 }}
              onChange={(next) => setStatus.mutate({ id: p.id, status: next })}
            />
          </Tooltip>
        </span>
      ),
    },
    {
      title: '',
      key: 'actions',
      render: (_, p) => (
        <span onClick={(e) => e.stopPropagation()}>
          <Space size={0}>
            <Button size="small" type="text" icon={<EditOutlined />} onClick={() => openForm(p)} />
            {/* Delete is here rather than in an edit form's action section because a
                position is not a record somebody maintains — it is a reading, and the only
                reason to remove one is that it was typed against the wrong ship. A reading
                that turned out to be wrong is history: that is what WITHDRAWN is for. */}
            <Popconfirm
              title="Delete this reading?"
              description="Only for a row entered by mistake — a report that turned out wrong is history worth keeping, and Withdrawn says so without losing it."
              onConfirm={() => remove.mutate(p.id)}
            >
              <Button size="small" type="text" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        </span>
      ),
    },
  ];

  return (
    <>
      <Form form={form} layout="vertical" onFinish={applyFilters}>
        <FilterPanel
          form={form}
          activeCount={countActiveFilters(filters)}
          onReset={() => {
            form.resetFields();
            applyFilters({});
          }}
          actions={
            <Button icon={<PlusOutlined />} onClick={() => openForm(null)}>
              New position
            </Button>
          }
          extras={
            <>
              <Form.Item name="current" valuePropName="checked" noStyle initialValue={true}>
                <Checkbox>
                  <Tooltip title="On, this is the fleet: the newest live reading per vessel. Off, every position ever reported — superseded ones included, which is where two brokers disagreeing is visible.">
                    Newest per vessel
                  </Tooltip>
                </Checkbox>
              </Form.Item>
              <Form.Item name="includeBanned" valuePropName="checked" noStyle>
                <Checkbox>Include banned (Russian-rooted)</Checkbox>
              </Form.Item>
            </>
          }
        >
          <Row gutter={12}>
            <Col xs={24} md={6}>
              <Form.Item
                name="vesselName"
                label="Vessel"
                tooltip="Matches the name she carries now or any former name on file."
              >
                <Input allowClear placeholder="current or former name" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="openAreaId" label="Open area">
                <TradeAreaSelect />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="openFrom"
                label="Open from"
                tooltip="Matches any open window overlapping this one, and always includes positions with no dates — SPOT names no day and is the promptest tonnage on the list."
              >
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="openTo" label="Open to">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={4}>
              <Form.Item
                name="minSize"
                label="Size min"
                tooltip="Reads DWCC where it is on file and DWT where it is not — position lists quote either, and testing DWCC alone would empty half the fleet out of every search."
              >
                <InputNumber style={{ width: '100%' }} min={0} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="maxSize" label="Size max">
                <InputNumber style={{ width: '100%' }} min={0} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="geared" label="Gear">
                <Select
                  allowClear
                  placeholder="Any"
                  options={[
                    { value: true, label: 'Geared' },
                    { value: false, label: 'Gearless' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item
                name="reportedWithinDays"
                label="Reported within"
                tooltip="A page of readings from three weeks ago is not a fleet, it is an archive."
              >
                <Select
                  allowClear
                  placeholder="Any age"
                  options={[
                    { value: 3, label: 'Last 3 days' },
                    { value: 7, label: 'Last week' },
                    { value: 14, label: 'Last fortnight' },
                    { value: 30, label: 'Last month' },
                  ]}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="reportedByCompanyId" label="Reported by">
                <CompanySelect allowClear placeholder="Any source" />
              </Form.Item>
            </Col>
          </Row>
          {/* Only meaningful with the "newest per vessel" box cleared — with it ticked the
              status is already LIVE by definition, so the API ignores this rather than
              letting the two AND into an empty page. */}
          {!current && (
            <Row gutter={12}>
              <Col xs={24} md={8}>
                <Form.Item name="status" label="Status">
                  <Select
                    mode="multiple"
                    allowClear
                    options={POSITION_STATUS_OPTIONS}
                    placeholder="Any status"
                    maxTagCount="responsive"
                  />
                </Form.Item>
              </Col>
            </Row>
          )}
        </FilterPanel>
      </Form>

      <ResponsiveTable<VesselPositionResponse>
        rowKey="id"
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={query.data?.content ?? []}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
        scroll={{ x: 1250 }}
        mobile={{
          title: (p) => (
            <Space size={4} wrap>
              {p.vessel.name}
              {p.status !== 'LIVE' && (
                <Tag color={POSITION_STATUS_META[p.status].color}>
                  {POSITION_STATUS_META[p.status].label}
                </Tag>
              )}
            </Space>
          ),
          subtitle: (p) =>
            `${p.openPortName ?? p.openPortText ?? p.openAreaName ?? 'no place given'} · ${formatOpenDates(
              p.openFrom,
              p.openTo,
              p.openText,
            )}`,
          fields: (p) => [
            { label: 'Size', value: formatFleetSize(p.vessel) },
            p.vessel.geared != null && {
              label: 'Gear',
              value: p.vessel.geared ? 'geared' : 'gearless',
            },
            p.lastCargo != null && { label: 'Last cargo', value: p.lastCargo },
            { label: 'Reported', value: staleness(p.ageDays).text },
            p.reportedByCompanyName != null && { label: 'By', value: p.reportedByCompanyName },
          ],
          actions: (p) => (
            <Button size="small" icon={<EditOutlined />} onClick={() => openForm(p)}>
              Edit
            </Button>
          ),
        }}
        mobileSort={[
          { field: 'reportedAt', label: 'Reported' },
          { field: 'openFrom', label: 'Open date' },
        ]}
        onRow={(p) => ({ onClick: () => setVesselId(p.vessel.id), style: { cursor: 'pointer' } })}
      />

      <PositionForm open={formOpen} editing={editing} onClose={() => setFormOpen(false)} />
      <VesselDrawer
        vesselId={vesselId}
        onClose={() => setVesselId(undefined)}
        onEdit={(v) => { setEditingVessel(v); setVesselFormOpen(true); }}
      />
      <VesselForm
        open={vesselFormOpen}
        editing={editingVessel}
        onClose={() => setVesselFormOpen(false)}
        // The drawer behind the form is showing the vessel that was just deleted.
        onDeleted={() => setVesselId(undefined)}
      />
    </>
  );
}
