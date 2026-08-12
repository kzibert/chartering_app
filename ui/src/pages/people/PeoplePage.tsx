import { useEffect, useState } from 'react';
import { Button, Card, Input, Popconfirm, Space, Table, Typography } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { usePeople, usePersonMutations } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import { usePersistedState } from '../../components/usePersistedState';
import GreetingName from '../../components/GreetingName';
import PersonForm from './PersonForm';
import PersonDrawer from './PersonDrawer';
import CompanyDrawer from '../companies/CompanyDrawer';
import CompanyForm from '../companies/CompanyForm';
import type { CompanyResponse, PersonResponse } from '../../api/types';

export default function PeoplePage() {
  const [companyId, setCompanyId] = usePersistedState<number | undefined>('people.companyId', undefined);
  const [name, setName] = usePersistedState<string>('people.name', '');

  // Filters as you type; the delay keeps a request per keystroke off the API.
  const [nameQuery, setNameQuery] = useState(name);
  useEffect(() => {
    const t = setTimeout(() => setNameQuery(name), 300);
    return () => clearTimeout(t);
  }, [name]);

  const { data, isLoading } = usePeople(companyId, nameQuery.trim() || undefined);
  const { remove } = usePersonMutations();
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<PersonResponse | null>(null);
  const [selectedId, setSelectedId] = useState<number>();
  const [companyDrawerId, setCompanyDrawerId] = useState<number>();
  const [companyFormOpen, setCompanyFormOpen] = useState(false);
  const [editingCompany, setEditingCompany] = useState<CompanyResponse | null>(null);

  const columns: ColumnsType<PersonResponse> = [
    {
      title: 'Full name',
      dataIndex: 'fullName',
      render: (fullName: string, p) => (
        <Typography.Link onClick={() => setSelectedId(p.id)}>{fullName}</Typography.Link>
      ),
    },
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
          <span>Name:</span>
          <Input
            allowClear
            style={{ width: 260 }}
            prefix={<SearchOutlined />}
            placeholder="Full name or greeting"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <span>Company:</span>
          <div style={{ width: 320 }}>
            <CompanySelect allowClear value={companyId} onChange={setCompanyId} placeholder="All companies" />
          </div>
          <Button
            disabled={!name && companyId == null}
            onClick={() => { setName(''); setCompanyId(undefined); }}
          >
            Reset
          </Button>
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
      <PersonDrawer
        personId={selectedId}
        onClose={() => setSelectedId(undefined)}
        onEdit={(p) => { setEditing(p); setFormOpen(true); }}
        onOpenCompany={setCompanyDrawerId}
      />
      <CompanyDrawer
        companyId={companyDrawerId}
        initialTab="people"
        onClose={() => setCompanyDrawerId(undefined)}
        onEdit={(c) => { setEditingCompany(c); setCompanyFormOpen(true); }}
      />
      <CompanyForm open={companyFormOpen} editing={editingCompany} onClose={() => setCompanyFormOpen(false)} />
    </>
  );
}
