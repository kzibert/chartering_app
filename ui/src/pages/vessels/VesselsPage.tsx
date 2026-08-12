import { useState } from 'react';
import { App, Button, Card, Checkbox, Col, Form, Input, InputNumber, Row, Select, Space, Table, Tag, Tooltip } from 'antd';
import { PlusOutlined, SearchOutlined, MailOutlined, StarOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useVessels, useVesselTypes, useFlags } from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import { usePersistedFilters } from '../../components/usePersistedState';
import { CONFIRMED_OPTIONS } from '../../components/filterOptions';
import ConfirmTag from '../../components/ConfirmTag';
import MultiCheckSelect from '../../components/MultiCheckSelect';
import { useVesselMutations } from '../../api/hooks';
import { vesselsApi } from '../../api/vessels';
import { useEmailList, contactToEntry } from '../../emailList/store';
import VesselDrawer from './VesselDrawer';
import VesselForm from './VesselForm';
import type { VesselFilter, VesselResponse } from '../../api/types';

export default function VesselsPage() {
  const [form] = Form.useForm();
  const [filters, setFilters] = usePersistedFilters<Partial<VesselFilter>>('vessels', form);
  const tc = useTableControls({ size: 20 }, 'vessels');
  const { data: types } = useVesselTypes();
  const { data: flags } = useFlags();
  const { confirm } = useVesselMutations();
  const { entries, addMany } = useEmailList();
  const { message } = App.useApp();

  const [selectedId, setSelectedId] = useState<number>();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<VesselResponse | null>(null);
  const [bulkLoading, setBulkLoading] = useState<'all' | 'confirmed' | 'main' | null>(null);

  // Pull the owner-company emails for the whole filtered vessel set into the email list.
  // mainOnly keeps one address per owner: its main email, or its first when none is flagged.
  const addOwnerEmails = async (confirmedOnly: boolean, mainOnly = false) => {
    setBulkLoading(mainOnly ? 'main' : confirmedOnly ? 'confirmed' : 'all');
    try {
      const contacts = await vesselsApi.ownerEmailContacts(filters, confirmedOnly, mainOnly);
      if (contacts.length === 0) {
        message.info('No matching email contacts for the filtered vessels');
        return;
      }
      const seen = new Set(entries.map((e) => e.contactId));
      const added = contacts.filter((c) => !seen.has(c.id)).length;
      addMany(contacts.map(contactToEntry));
      const dup = contacts.length - added;
      message.success(
        `Added ${added} email${added === 1 ? '' : 's'} to the list` +
          (dup ? ` (${dup} already there)` : ''),
      );
    } catch {
      /* the axios interceptor surfaces the error */
    } finally {
      setBulkLoading(null);
    }
  };

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
          <Row gutter={12}>
            <Col xs={12} md={3}><Form.Item name="minDwt" label="DWT min"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxDwt" label="DWT max"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="minDwcc" label="DWCC min"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxDwcc" label="DWCC max"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="minGrain" label="Grain min"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxGrain" label="Grain max"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="minBale" label="Bale min"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxBale" label="Bale max"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={3}><Form.Item name="minDraft" label="Draft min"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxDraft" label="Draft max"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="minYear" label="Year min"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
            <Col xs={12} md={3}><Form.Item name="maxYear" label="Year max"><InputNumber style={{ width: '100%' }} /></Form.Item></Col>
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
            <Tooltip title="Add every email of the owner companies of all vessels matching the current filters to the Email list">
              <Button
                icon={<MailOutlined />}
                loading={bulkLoading === 'all'}
                disabled={bulkLoading !== null}
                onClick={() => addOwnerEmails(false)}
              >
                Add all emails to list
              </Button>
            </Tooltip>
            <Tooltip title="Same, but only confirmed emails">
              <Button
                icon={<MailOutlined />}
                loading={bulkLoading === 'confirmed'}
                disabled={bulkLoading !== null}
                onClick={() => addOwnerEmails(true)}
              >
                Add confirmed emails to list
              </Button>
            </Tooltip>
            <Tooltip title="One email per owner company: the one marked main, or its first email if none is marked">
              <Button
                icon={<StarOutlined />}
                loading={bulkLoading === 'main'}
                disabled={bulkLoading !== null}
                onClick={() => addOwnerEmails(false, true)}
              >
                Add main emails to list
              </Button>
            </Tooltip>
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
