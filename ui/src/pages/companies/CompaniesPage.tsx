import { useState } from 'react';
import { Button, Card, Checkbox, Col, Form, Input, Row, Select, Space, Table, Tag } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useCompanies, useRegions, usePorts, useTonnageCategories, useCompanyMutations } from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import ConfirmTag from '../../components/ConfirmTag';
import CompanyDrawer from './CompanyDrawer';
import CompanyForm from './CompanyForm';
import type { CompanyFilter, CompanyResponse } from '../../api/types';

export default function CompaniesPage() {
  const [form] = Form.useForm();
  const [filters, setFilters] = useState<Partial<CompanyFilter>>({});
  const tc = useTableControls();
  const { data: regions } = useRegions();
  const { data: ports } = usePorts();
  const { data: tonnage } = useTonnageCategories();
  const { confirm } = useCompanyMutations();

  const [selectedId, setSelectedId] = useState<number>();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<CompanyResponse | null>(null);

  const query = useCompanies({ ...filters, page: tc.state.page, size: tc.state.size, sort: tc.state.sort });

  const applyFilters = (values: Partial<CompanyFilter>) => {
    // unchecked role booleans come through as false; drop them so they don't over-filter
    const cleaned = { ...values };
    (['shipowner', 'charterer', 'broker', 'agent'] as const).forEach((k) => {
      if (!cleaned[k]) delete cleaned[k];
    });
    setFilters(cleaned);
    tc.resetPage();
  };

  const columns: ColumnsType<CompanyResponse> = [
    {
      title: 'Name',
      dataIndex: 'name',
      sorter: true,
      render: (name: string, c) => (
        <Space size={4}>
          {name}
          {!c.legacy && <Tag color="green">new</Tag>}
          {c.banned && <Tag color="red">banned</Tag>}
        </Space>
      ),
    },
    { title: 'City', dataIndex: 'cityName' },
    {
      title: 'Roles',
      key: 'roles',
      render: (_, c) => (
        <Space size={4} wrap>
          {c.shipowner && <span>owner</span>}
          {c.charterer && <span>charterer</span>}
          {c.broker && <span>broker</span>}
          {c.agent && <span>agent</span>}
        </Space>
      ),
    },
    {
      title: 'Status',
      key: 'confirmed',
      render: (_, c) => (
        <ConfirmTag
          confirmed={c.confirmed}
          confirmedAt={c.confirmedAt}
          confirmedBy={c.confirmedBy}
          loading={confirm.isPending}
          onConfirm={(body) => confirm.mutate({ id: c.id, confirmed: true, body })}
          onUnconfirm={() => confirm.mutate({ id: c.id, confirmed: false })}
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
            <Col xs={12} md={6}><Form.Item name="city" label="City"><Input allowClear /></Form.Item></Col>
            <Col xs={12} md={4}>
              <Form.Item name="confirmed" label="Confirmed">
                <Select allowClear options={[{ value: true, label: 'Confirmed' }, { value: false, label: 'Needs confirm' }]} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={6}>
              <Form.Item name="regionId" label="Region (trading)">
                <Select allowClear showSearch optionFilterProp="label" options={(regions ?? []).map((r) => ({ value: r.id, label: r.name }))} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="portId" label="Port">
                <Select allowClear showSearch optionFilterProp="label" options={(ports ?? []).map((p) => ({ value: p.id, label: p.name }))} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="tonnageCategoryId" label="Tonnage category">
                <Select allowClear showSearch optionFilterProp="label" options={(tonnage ?? []).map((t) => ({ value: t.id, label: t.name }))} />
              </Form.Item>
            </Col>
            <Col xs={24} md={6}>
              <Form.Item label="Roles">
                <Space wrap>
                  <Form.Item name="shipowner" valuePropName="checked" noStyle><Checkbox>Owner</Checkbox></Form.Item>
                  <Form.Item name="charterer" valuePropName="checked" noStyle><Checkbox>Charterer</Checkbox></Form.Item>
                  <Form.Item name="broker" valuePropName="checked" noStyle><Checkbox>Broker</Checkbox></Form.Item>
                  <Form.Item name="agent" valuePropName="checked" noStyle><Checkbox>Agent</Checkbox></Form.Item>
                </Space>
              </Form.Item>
            </Col>
          </Row>
          <Space wrap>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>Search</Button>
            <Button onClick={() => { form.resetFields(); applyFilters({}); }}>Reset</Button>
            <Button icon={<PlusOutlined />} onClick={() => { setEditing(null); setFormOpen(true); }}>New company</Button>
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

      <Table<CompanyResponse>
        rowKey="id"
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={query.data?.content ?? []}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
        onRow={(c) => ({ onClick: () => setSelectedId(c.id), style: { cursor: 'pointer' } })}
      />

      <CompanyDrawer
        companyId={selectedId}
        onClose={() => setSelectedId(undefined)}
        onEdit={(c) => { setEditing(c); setFormOpen(true); }}
      />
      <CompanyForm open={formOpen} editing={editing} onClose={() => setFormOpen(false)} />
    </>
  );
}
