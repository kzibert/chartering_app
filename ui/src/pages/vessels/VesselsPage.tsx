import { useState } from 'react';
import { Button, Card, Checkbox, Col, Form, Input, InputNumber, Row, Select, Space, Table, Tag } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useVessels, useVesselTypes, useFlags } from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import { usePersistedFilters } from '../../components/usePersistedState';
import { CONFIRMED_OPTIONS } from '../../components/filterOptions';
import ConfirmTag from '../../components/ConfirmTag';
import MultiCheckSelect from '../../components/MultiCheckSelect';
import { useVesselMutations } from '../../api/hooks';
import { collectApi } from '../../api/circulations';
import AddToListActions from '../../components/AddToListActions';
import VesselDrawer from './VesselDrawer';
import VesselForm from './VesselForm';
import type { VesselFilter, VesselResponse } from '../../api/types';

// DWT/DWCC and grain/bale are alternative statements of the same measurement and are
// rarely both recorded, so each pair is matched with OR rather than AND.
const DEADWEIGHT_HINT =
  'DWT and DWCC are matched with OR: fill either or both, and a vessel matching one of ' +
  'the ranges is returned. Vessels with no figure on file for a range you filled are not.';
const CAPACITY_HINT =
  'Grain and bale are matched with OR: fill either or both, and a vessel matching one of ' +
  'the ranges is returned. Vessels with no figure on file for a range you filled are not.';

export default function VesselsPage() {
  const [form] = Form.useForm();
  // Key bumped to .v2 when minDraft/minYear/maxYear were dropped: a search saved under the
  // old key would keep sending removed parameters that no longer have boxes on screen.
  const [filters, setFilters] = usePersistedFilters<Partial<VesselFilter>>('vessels.v2', form);
  const tc = useTableControls({ size: 20 }, 'vessels');
  const { data: types } = useVesselTypes();
  const { data: flags } = useFlags();
  const { confirm } = useVesselMutations();

  const [selectedId, setSelectedId] = useState<number>();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<VesselResponse | null>(null);
  // Ticked rows for the bulk add. Kept across pages so a selection spanning two pages of
  // results survives paging — the ids are what the API is given, not the visible rows.
  const [selectedIds, setSelectedIds] = useState<number[]>([]);

  const query = useVessels({
    ...filters,
    page: tc.state.page,
    size: tc.state.size,
    sort: tc.state.sort,
  });

  const applyFilters = (values: Partial<VesselFilter>) => {
    setFilters(values);
    tc.resetPage();
  };

  const columns: ColumnsType<VesselResponse> = [
    {
      title: 'Name',
      dataIndex: 'name',
      sorter: true,
      sortOrder: tc.sortOrderFor('name'),
      fixed: 'left',
      render: (name: string, v) => (
        <Space size={4}>
          {name}
          {!v.legacy && <Tag color="green">new</Tag>}
          {v.banned && <Tag color="red">banned</Tag>}
        </Space>
      ),
    },
    { title: 'IMO', dataIndex: 'imoNumber' },
    { title: 'DWT', dataIndex: 'deadweightTonnage', sorter: true, sortOrder: tc.sortOrderFor('deadweightTonnage') },
    { title: 'DWCC', dataIndex: 'deadweightCargoCapacity', sorter: true, sortOrder: tc.sortOrderFor('deadweightCargoCapacity') },
    { title: 'Grain m³', dataIndex: 'grainCapacityM3', sorter: true, sortOrder: tc.sortOrderFor('grainCapacityM3') },
    { title: 'Bale m³', dataIndex: 'baleCapacityM3' },
    { title: 'Draft', dataIndex: 'maximumDraft' },
    { title: 'Year', dataIndex: 'yearBuilt', sorter: true, sortOrder: tc.sortOrderFor('yearBuilt') },
    { title: 'Type', dataIndex: 'vesselType' },
    { title: 'Flag', dataIndex: 'flag' },
    { title: 'Owner', dataIndex: 'ownerName' },
    {
      title: 'Status',
      key: 'confirmed',
      render: (_, v) => (
        <ConfirmTag
          confirmed={v.confirmed}
          confirmedAt={v.confirmedAt}
          confirmedBy={v.confirmedBy}
          loading={confirm.isPending}
          onConfirm={(body) => confirm.mutate({ id: v.id, confirmed: true, body })}
          onUnconfirm={() => confirm.mutate({ id: v.id, confirmed: false })}
        />
      ),
    },
  ];

  return (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Form form={form} layout="vertical" onFinish={applyFilters}>
          <Row gutter={12}>
            <Col xs={12} md={6}><Form.Item name="name" label="Name"><Input allowClear /></Form.Item></Col>
            <Col xs={12} md={6}><Form.Item name="imoNumber" label="IMO"><Input allowClear /></Form.Item></Col>
            <Col xs={12} md={6}><Form.Item name="companyName" label="Company" tooltip="Matches the owner or any broker linked to the vessel"><Input allowClear placeholder="owner or broker" /></Form.Item></Col>
            <Col xs={12} md={6}>
              <Form.Item name="confirmed" label="Confirmed" initialValue="">
                <Select options={CONFIRMED_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>
          {/* The tooltips carry the OR rule: with eight bare number boxes in a row there is
              otherwise nothing on screen saying which of them combine and how. */}
          <Row gutter={12}>
            <Col xs={12} md={3}><Form.Item name="minDwt" label="DWT min" tooltip={DEADWEIGHT_HINT}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxDwt" label="DWT max" tooltip={DEADWEIGHT_HINT}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="minDwcc" label="DWCC min" tooltip={DEADWEIGHT_HINT}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxDwcc" label="DWCC max" tooltip={DEADWEIGHT_HINT}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="minGrain" label="Grain min" tooltip={CAPACITY_HINT}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxGrain" label="Grain max" tooltip={CAPACITY_HINT}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="minBale" label="Bale min" tooltip={CAPACITY_HINT}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxBale" label="Bale max" tooltip={CAPACITY_HINT}><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={3}>
              <Form.Item name="maxDraft" label="Max draft" tooltip="Deepest draft you can accept. Vessels with no draft on file are not returned.">
                <InputNumber style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={3}>
              <Form.Item name="yearFrom" label="Built from" tooltip="Oldest build year you will accept — shows that year and younger. Vessels with no year on file are not returned.">
                <InputNumber style={{ width: '100%' }} placeholder="e.g. 2005" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="vesselType" label="Type">
                <MultiCheckSelect options={types ?? []} placeholder="Any type" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="flag" label="Flag">
                <MultiCheckSelect options={flags ?? []} placeholder="Any flag" />
              </Form.Item>
            </Col>
          </Row>
          <Space wrap>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>Search</Button>
            <Button onClick={() => { form.resetFields(); applyFilters({}); }}>Reset</Button>
            <Button icon={<PlusOutlined />} onClick={() => { setEditing(null); setFormOpen(true); }}>New vessel</Button>
            <AddToListActions
              entity="vessels"
              selectedIds={selectedIds}
              totalMatching={query.data?.totalElements ?? 0}
              collect={(ids, confirmedOnly) =>
                collectApi.fromVessels(filters, ids, confirmedOnly)
              }
              onCleared={() => setSelectedIds([])}
            />
            <Form.Item name="legacy" noStyle initialValue="">
              <Select
                style={{ width: 180 }}
                options={[
                  { value: '', label: 'Source: all' },
                  { value: false, label: 'New (app)' },
                  { value: true, label: 'Legacy (imported)' },
                ]}
              />
            </Form.Item>
            <Form.Item name="includeBanned" valuePropName="checked" noStyle>
              <Checkbox>Include banned (Russian-rooted)</Checkbox>
            </Form.Item>
          </Space>
        </Form>
      </Card>

      <Table<VesselResponse>
        rowKey="id"
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={query.data?.content ?? []}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
        scroll={{ x: 1300 }}
        rowSelection={{
          selectedRowKeys: selectedIds,
          // preserveSelectedRowKeys keeps ticks from other pages, which are no longer in
          // dataSource — without it, paging away silently drops half the selection.
          preserveSelectedRowKeys: true,
          onChange: (keys) => setSelectedIds(keys as number[]),
        }}
        onRow={(v) => ({ onClick: () => setSelectedId(v.id), style: { cursor: 'pointer' } })}
      />

      <VesselDrawer
        vesselId={selectedId}
        onClose={() => setSelectedId(undefined)}
        onEdit={(v) => { setEditing(v); setFormOpen(true); }}
      />
      <VesselForm open={formOpen} editing={editing} onClose={() => setFormOpen(false)} />
    </>
  );
}
