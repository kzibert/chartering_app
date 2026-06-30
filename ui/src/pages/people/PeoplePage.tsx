import { useState } from 'react';
import { Button, Card, Popconfirm, Space, Table, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { usePeople, usePersonMutations } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import GreetingName from '../../components/GreetingName';
import PersonForm from './PersonForm';
import type { PersonResponse } from '../../api/types';

export default function PeoplePage() {
  const [companyId, setCompanyId] = useState<number>();
  const { data, isLoading } = usePeople(companyId);
  const { remove } = usePersonMutations();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<PersonResponse | null>(null);

  const columns: ColumnsType<PersonResponse> = [
    { title: 'Full name', dataIndex: 'fullName' },
    {
      title: 'Greeting',
      key: 'greeting',
      width: 220,
      render: (_, p) =>
        p.greetingName ? (
          <GreetingName title={p.title} name={p.greetingName} />
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    { title: 'Company', dataIndex: 'companyName' },
    { title: 'Notes', dataIndex: 'notes', ellipsis: true },
    {
      title: 'Actions',
      key: 'actions',
      width: 160,
      render: (_, p) => (
        <Space>
          <Button size="small" onClick={() => { setEditing(p); setFormOpen(true); }}>Edit</Button>
          <Popconfirm title="Delete this person?" onConfirm={() => remove.mutate(p.id)}>
            <Button size="small" danger>Delete</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Space wrap>
          <span>Filter by company:</span>
          <div style={{ width: 320 }}>
            <CompanySelect allowClear value={companyId} onChange={setCompanyId} placeholder="All companies" />
          </div>
          <Button icon={<PlusOutlined />} onClick={() => { setEditing(null); setFormOpen(true); }}>New person</Button>
        </Space>
      </Card>

      <Table<PersonResponse>
        rowKey="id"
        size="small"
        loading={isLoading}
        columns={columns}
        dataSource={data ?? []}
        pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (t) => `${t} total` }}
      />

      <PersonForm open={formOpen} editing={editing} onClose={() => setFormOpen(false)} />
    </>
  );
}
