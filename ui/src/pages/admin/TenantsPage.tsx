import { useEffect, useState } from 'react';
import { Button, Card, Divider, Form, Input, Modal, Popconfirm, Space, Tag, Typography } from 'antd';
import { CheckOutlined, EditOutlined, PlusOutlined, StopOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import ResponsiveTable from '../../components/ResponsiveTable';
import { tenantsApi, type TenantResponse } from '../../api/tenants';
import type { UserPasswordResponse } from '../../api/users';
import { useSession } from '../../auth/session';
import { MIN_PASSWORD_LENGTH } from '../../auth/ChangePasswordForm';
import { IssuedPasswordModal } from './UsersPage';

/**
 * The desks on this installation, for the platform administrator.
 *
 * A desk is created together with its first administrator, who then makes the rest of the
 * desk's accounts on their own Users screen. Nothing here opens a desk's data: managing desks
 * and reading what is on them are different powers. There is no delete — a desk's rows all
 * hang off it — and suspending is the reversible way to stop one.
 */
export default function TenantsPage() {
  const session = useSession();
  const qc = useQueryClient();
  const tenants = useQuery({ queryKey: ['admin', 'tenants'], queryFn: tenantsApi.list });
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<TenantResponse | null>(null);
  const [issued, setIssued] = useState<UserPasswordResponse | null>(null);

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['admin', 'tenants'] });
    qc.invalidateQueries({ queryKey: ['admin', 'users'] });
  };

  const statusTag = (t: TenantResponse) =>
    t.status === 'ACTIVE' ? <Tag color="green">Active</Tag> : <Tag color="red">Suspended</Tag>;

  const editButton = (t: TenantResponse) => (
    <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(t)}>
      Edit
    </Button>
  );

  const columns = [
    {
      title: 'Desk',
      dataIndex: 'name',
      render: (_: unknown, t: TenantResponse) => (
        <span>
          <Typography.Text strong>{t.name}</Typography.Text>
          {t.id === session.tenantId && <Tag style={{ marginInlineStart: 8 }}>Yours</Tag>}
        </span>
      ),
    },
    { title: 'Status', key: 'status', render: (_: unknown, t: TenantResponse) => statusTag(t) },
    { title: 'Accounts', dataIndex: 'users' },
    {
      title: 'Created',
      dataIndex: 'createdAt',
      render: (v: string) => dayjs(v).format('DD MMM YYYY'),
    },
    { title: '', key: 'actions', width: 90, render: (_: unknown, t: TenantResponse) => editButton(t) },
  ];

  return (
    <Card
      title="Desks"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
          New desk
        </Button>
      }
    >
      <ResponsiveTable<TenantResponse>
        rowKey="id"
        loading={tenants.isLoading}
        dataSource={tenants.data ?? []}
        columns={columns}
        pagination={false}
        mobile={{
          title: (t) => t.name,
          fields: (t) => [
            { label: 'Status', value: statusTag(t) },
            { label: 'Accounts', value: t.users },
          ],
          actions: editButton,
        }}
      />

      <CreateTenantModal
        open={creating}
        onClose={() => setCreating(false)}
        onCreated={(admin) => {
          setCreating(false);
          refresh();
          setIssued(admin);
        }}
      />

      <EditTenantModal
        tenant={editing}
        isOwn={editing?.id === session.tenantId}
        onClose={() => setEditing(null)}
        onChanged={(t) => {
          setEditing(t);
          refresh();
        }}
      />

      <IssuedPasswordModal issued={issued} onClose={() => setIssued(null)} />
    </Card>
  );
}

function CreateTenantModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  onCreated: (admin: UserPasswordResponse) => void;
}) {
  const [form] = Form.useForm();
  const create = useMutation({
    mutationFn: tenantsApi.create,
    onSuccess: (res) => onCreated(res.admin),
  });

  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  return (
    <Modal
      open={open}
      title="New desk"
      okText="Create"
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={create.isPending}
      destroyOnClose
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={(v) => create.mutate({ ...v, adminPassword: v.adminPassword || undefined })}
      >
        <Form.Item name="name" label="Desk name" rules={[{ required: true, message: 'A name is required' }]}>
          <Input placeholder="As its people would call it" />
        </Form.Item>
        <Divider orientation="left" plain>
          Its first administrator
        </Divider>
        <Form.Item
          name="adminUsername"
          label="Username"
          rules={[
            { required: true, message: 'Username is required' },
            { min: 3, message: 'At least 3 characters' },
            { pattern: /^\S+$/, message: 'No spaces' },
          ]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="adminDisplayName" label="Name">
          <Input />
        </Form.Item>
        <Form.Item
          name="adminPassword"
          label="Password"
          extra="Leave empty and one is generated and shown once. Either way they choose their own at first login."
          rules={[{ min: MIN_PASSWORD_LENGTH, message: `At least ${MIN_PASSWORD_LENGTH} characters` }]}
        >
          <Input.Password autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

function EditTenantModal({
  tenant,
  isOwn,
  onClose,
  onChanged,
}: {
  tenant: TenantResponse | null;
  isOwn: boolean;
  onClose: () => void;
  onChanged: (t: TenantResponse) => void;
}) {
  const [form] = Form.useForm();
  const rename = useMutation({
    mutationFn: (name: string) => tenantsApi.rename(tenant!.id, name),
    onSuccess: (t) => {
      onChanged(t);
      onClose();
    },
  });
  const toggle = useMutation({
    mutationFn: () =>
      tenant!.status === 'ACTIVE' ? tenantsApi.suspend(tenant!.id) : tenantsApi.activate(tenant!.id),
    onSuccess: onChanged,
  });

  useEffect(() => {
    if (tenant) form.setFieldsValue({ name: tenant.name });
  }, [tenant, form]);

  const active = tenant?.status === 'ACTIVE';

  return (
    <Modal
      open={!!tenant}
      title={tenant ? `Edit ${tenant.name}` : ''}
      okText="Save"
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={rename.isPending}
      destroyOnClose
    >
      <Form form={form} layout="vertical" onFinish={(v) => rename.mutate(v.name)}>
        <Form.Item name="name" label="Desk name" rules={[{ required: true, message: 'A name is required' }]}>
          <Input />
        </Form.Item>
      </Form>

      {tenant && !isOwn && (
        <>
          <Divider orientation="left" plain style={{ marginTop: 8 }}>
            This desk
          </Divider>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
            Takes effect straight away — it is not saved with the form, and Cancel does not undo it.
          </Typography.Paragraph>
          <Space wrap>
            <Popconfirm
              title={active ? `Suspend ${tenant.name}?` : `Reactivate ${tenant.name}?`}
              description={
                active
                  ? 'Everyone on it is logged out and cannot log in, and its mail and sweeps stop. Its data is kept.'
                  : 'Its accounts can log in again and its background work resumes.'
              }
              okText={active ? 'Suspend' : 'Reactivate'}
              okButtonProps={{ danger: active }}
              onConfirm={() => toggle.mutate()}
            >
              <Button danger={active} icon={active ? <StopOutlined /> : <CheckOutlined />} loading={toggle.isPending}>
                {active ? 'Suspend' : 'Reactivate'}
              </Button>
            </Popconfirm>
          </Space>
        </>
      )}
    </Modal>
  );
}
