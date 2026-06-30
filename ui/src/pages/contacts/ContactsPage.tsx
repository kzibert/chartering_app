import { useState } from 'react';
import { Button, Card, Checkbox, Col, Form, Input, Popconfirm, Row, Select, Space, Table, Tag } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useContacts, useContactMutations } from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import ConfirmTag from '../../components/ConfirmTag';
import CopyableValue from '../../components/CopyableValue';
import AddToListButton from '../../components/AddToListButton';
import BanButton from '../../components/BanButton';
import ContactForm from './ContactForm';
import type { ContactFilter, ContactResponse } from '../../api/types';

export default function ContactsPage() {
  const [form] = Form.useForm();
  const [filters, setFilters] = useState<Partial<ContactFilter>>({});
  const tc = useTableControls();
  const { confirm, remove, ban } = useContactMutations();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ContactResponse | null>(null);

  const query = useContacts({ ...filters, page: tc.state.page, size: tc.state.size });

  const applyFilters = (values: Partial<ContactFilter>) => {
    setFilters(values);
    tc.resetPage();
  };

  const columns: ColumnsType<ContactResponse> = [
    {
      title: 'Kind',
      dataIndex: 'contactKind',
      width: 90,
      render: (k: string) => <Tag color={k === 'email' ? 'blue' : 'default'}>{k}</Tag>,
    },
    {
      title: 'Value',
      dataIndex: 'contactValue',
      render: (v: string, c) => (
        <Space size={4}>
          <CopyableValue value={v} />
          {!c.legacy && <Tag color="green">new</Tag>}
          {c.banned && <Tag color="red">banned</Tag>}
        </Space>
      ),
    },
    { title: 'Person', dataIndex: 'personName' },
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
    {
      title: 'Actions',
      key: 'actions',
      width: 260,
      render: (_, c) => (
        <Space wrap>
          <AddToListButton ct={c} />
          <Button size="small" onClick={() => { setEditing(c); setFormOpen(true); }}>Edit</Button>
          <BanButton
            banned={c.banned}
            loading={ban.isPending}
            size="small"
            onToggle={(b) => ban.mutate({ id: c.id, banned: b })}
          />
          <Popconfirm title="Delete this contact?" onConfirm={() => remove.mutate(c.id)}>
            <Button size="small" danger>Delete</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Form form={form} layout="vertical" onFinish={applyFilters}>
          <Row gutter={12}>
            <Col xs={12} md={5}>
              <Form.Item name="kind" label="Kind">
                <Select allowClear options={[{ value: 'email', label: 'email' }, { value: 'phone', label: 'phone' }]} />
              </Form.Item>
            </Col>
            <Col xs={12} md={7}><Form.Item name="value" label="Value contains"><Input allowClear /></Form.Item></Col>
            <Col xs={12} md={5}><Form.Item name="companyId" label="Company ID"><Input allowClear /></Form.Item></Col>
            <Col xs={12} md={5}>
              <Form.Item name="confirmed" label="Confirmed">
                <Select allowClear options={[{ value: true, label: 'Confirmed' }, { value: false, label: 'Needs confirm' }]} />
              </Form.Item>
            </Col>
          </Row>
          <Space wrap>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>Search</Button>
            <Button onClick={() => { form.resetFields(); applyFilters({}); }}>Reset</Button>
            <Button icon={<PlusOutlined />} onClick={() => { setEditing(null); setFormOpen(true); }}>New contact</Button>
            <Form.Item name="legacy" noStyle>
              <Select
                allowClear
                placeholder="Source: all"
                style={{ width: 180 }}
                options={[
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

      <Table<ContactResponse>
        rowKey="id"
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={query.data?.content ?? []}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
      />

      <ContactForm open={formOpen} editing={editing} onClose={() => setFormOpen(false)} />
    </>
  );
}
