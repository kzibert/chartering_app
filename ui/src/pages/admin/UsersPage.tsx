import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Divider,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import { EditOutlined, KeyOutlined, PlusOutlined, StopOutlined, CheckOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import ResponsiveTable from '../../components/ResponsiveTable';
import { usersApi, type UserPasswordResponse, type UserResponse } from '../../api/users';
import { ROLE_LABELS, type UserRole } from '../../api/auth';
import { useSession } from '../../auth/session';
import { MIN_PASSWORD_LENGTH } from '../../auth/ChangePasswordForm';
import { tenantsApi } from '../../api/tenants';

const roleColor: Record<UserRole, string> = {
  USER: 'default',
  TENANT_ADMIN: 'blue',
  PLATFORM_ADMIN: 'purple',
};

/**
 * The accounts on this desk — or, for a platform administrator, on every desk.
 *
 * Nobody registers themselves: an account exists because somebody on this screen made it.
 * The password it starts with is either typed here or chosen by the server and shown once,
 * and either way the person has to replace it at their first login, because an
 * administrator has seen it.
 *
 * Disable and reset sit at the foot of the edit form, not on the row, for the reason
 * RecordActions gives: a list is a screen you read, and the edit form is the one place you
 * arrive at by saying you mean to change an account. There is no delete — an account's name
 * is on the change log and on every reply it sent.
 */
export default function UsersPage() {
  const session = useSession();
  const isPlatform = session.role === 'PLATFORM_ADMIN';
  const qc = useQueryClient();
  const users = useQuery({ queryKey: ['admin', 'users'], queryFn: () => usersApi.list() });

  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<UserResponse | null>(null);
  const [issued, setIssued] = useState<UserPasswordResponse | null>(null);

  const refresh = () => qc.invalidateQueries({ queryKey: ['admin', 'users'] });

  const statusTags = (u: UserResponse) => (
    <Space size={4} wrap>
      {u.enabled ? <Tag color="green">Active</Tag> : <Tag>Disabled</Tag>}
      {u.locked && <Tag color="red">Locked out</Tag>}
      {u.mustChangePassword && u.enabled && <Tag color="gold">Password to choose</Tag>}
    </Space>
  );

  const columns = [
    {
      title: 'Username',
      dataIndex: 'username',
      render: (_: unknown, u: UserResponse) => (
        <div>
          <Typography.Text strong>{u.username}</Typography.Text>
          {u.id === session.userId && <Tag style={{ marginInlineStart: 8 }}>You</Tag>}
          {u.displayName && (
            <div>
              <Typography.Text type="secondary">{u.displayName}</Typography.Text>
            </div>
          )}
        </div>
      ),
    },
    {
      title: 'Role',
      dataIndex: 'role',
      render: (r: UserRole) => <Tag color={roleColor[r]}>{ROLE_LABELS[r]}</Tag>,
    },
    ...(isPlatform ? [{ title: 'Desk', dataIndex: 'tenantName' }] : []),
    { title: 'Status', key: 'status', render: (_: unknown, u: UserResponse) => statusTags(u) },
    {
      title: 'Last login',
      dataIndex: 'lastLoginAt',
      render: (v?: string) => (v ? dayjs(v).format('DD MMM YYYY HH:mm') : 'Never'),
    },
    {
      title: '',
      key: 'actions',
      width: 90,
      render: (_: unknown, u: UserResponse) => (
        <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(u)}>
          Edit
        </Button>
      ),
    },
  ];

  return (
    <Card
      title="Users"
      extra={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
          New user
        </Button>
      }
    >
      <ResponsiveTable<UserResponse>
        rowKey="id"
        loading={users.isLoading}
        dataSource={users.data ?? []}
        columns={columns}
        pagination={false}
        mobile={{
          title: (u) => u.username,
          subtitle: (u) => [u.displayName, isPlatform ? u.tenantName : undefined].filter(Boolean).join(' · '),
          fields: (u) => [
            { label: 'Role', value: ROLE_LABELS[u.role] },
            { label: 'Status', value: statusTags(u) },
            { label: 'Last login', value: u.lastLoginAt ? dayjs(u.lastLoginAt).format('DD MMM HH:mm') : 'Never' },
          ],
          actions: (u) => (
            <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(u)}>
              Edit
            </Button>
          ),
        }}
      />

      <CreateUserModal
        open={creating}
        isPlatform={isPlatform}
        onClose={() => setCreating(false)}
        onCreated={(res) => {
          setCreating(false);
          refresh();
          setIssued(res);
        }}
      />

      <EditUserModal
        user={editing}
        isPlatform={isPlatform}
        isSelf={editing?.id === session.userId}
        onClose={() => setEditing(null)}
        onChanged={(u) => {
          setEditing(u);
          refresh();
        }}
        onIssued={(res) => {
          refresh();
          setIssued(res);
        }}
      />

      <IssuedPasswordModal issued={issued} onClose={() => setIssued(null)} />
    </Card>
  );
}

function roleOptions(isPlatform: boolean) {
  const roles: UserRole[] = isPlatform ? ['USER', 'TENANT_ADMIN', 'PLATFORM_ADMIN'] : ['USER', 'TENANT_ADMIN'];
  return roles.map((r) => ({ value: r, label: ROLE_LABELS[r] }));
}

function CreateUserModal({
  open,
  isPlatform,
  onClose,
  onCreated,
}: {
  open: boolean;
  isPlatform: boolean;
  onClose: () => void;
  onCreated: (res: UserPasswordResponse) => void;
}) {
  const [form] = Form.useForm();
  const session = useSession();
  const create = useMutation({ mutationFn: usersApi.create, onSuccess: onCreated });
  // Only a platform administrator chooses the desk; everybody else creates on their own.
  const desks = useQuery({
    queryKey: ['admin', 'tenants'],
    queryFn: tenantsApi.list,
    enabled: isPlatform && open,
  });

  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  return (
    <Modal
      open={open}
      title="New user"
      okText="Create"
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={create.isPending}
      destroyOnClose
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ role: 'USER', tenantId: session.tenantId }}
        onFinish={(v) => create.mutate({ ...v, password: v.password || undefined })}
      >
        <Form.Item
          name="username"
          label="Username"
          extra="What they log in with — an email address works well. It cannot be changed later."
          rules={[
            { required: true, message: 'Username is required' },
            { min: 3, message: 'At least 3 characters' },
            { pattern: /^\S+$/, message: 'No spaces' },
          ]}
        >
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="displayName" label="Name">
          <Input placeholder="As colleagues know them" />
        </Form.Item>
        <Form.Item name="role" label="Role" rules={[{ required: true }]}>
          <Select options={roleOptions(isPlatform)} />
        </Form.Item>
        {isPlatform && (
          <Form.Item name="tenantId" label="Desk" rules={[{ required: true }]}>
            <Select
              loading={desks.isLoading}
              options={(desks.data ?? []).map((d) => ({ value: d.id, label: d.name }))}
            />
          </Form.Item>
        )}
        <Form.Item
          name="password"
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

function EditUserModal({
  user,
  isPlatform,
  isSelf,
  onClose,
  onChanged,
  onIssued,
}: {
  user: UserResponse | null;
  isPlatform: boolean;
  isSelf: boolean;
  onClose: () => void;
  onChanged: (u: UserResponse) => void;
  onIssued: (res: UserPasswordResponse) => void;
}) {
  const [form] = Form.useForm();
  const update = useMutation({
    mutationFn: (v: { displayName?: string; role: UserRole }) => usersApi.update(user!.id, v),
    onSuccess: (u) => {
      onChanged(u);
      onClose();
    },
  });
  const toggle = useMutation({
    mutationFn: () => (user!.enabled ? usersApi.disable(user!.id) : usersApi.enable(user!.id)),
    onSuccess: onChanged,
  });
  const reset = useMutation({
    mutationFn: () => usersApi.resetPassword(user!.id),
    onSuccess: (res) => {
      onChanged(res.user);
      onIssued(res);
    },
  });

  useEffect(() => {
    if (user) form.setFieldsValue({ displayName: user.displayName, role: user.role });
  }, [user, form]);

  // A desk administrator may read a platform administrator's row but not change it; the
  // server says so too, this only keeps the buttons from promising otherwise.
  const readOnly = !!user && user.role === 'PLATFORM_ADMIN' && !isPlatform;

  return (
    <Modal
      open={!!user}
      title={user ? `Edit ${user.username}` : ''}
      okText="Save"
      onCancel={onClose}
      onOk={() => form.submit()}
      okButtonProps={{ disabled: readOnly }}
      confirmLoading={update.isPending}
      destroyOnClose
    >
      {readOnly && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="Only a platform administrator can change a platform administrator's account."
        />
      )}
      <Form form={form} layout="vertical" disabled={readOnly} onFinish={(v) => update.mutate(v)}>
        <Form.Item name="displayName" label="Name">
          <Input />
        </Form.Item>
        <Form.Item
          name="role"
          label="Role"
          extra={isSelf ? 'You cannot change your own role.' : undefined}
        >
          <Select
            disabled={isSelf}
            options={roleOptions(isPlatform || user?.role === 'PLATFORM_ADMIN')}
          />
        </Form.Item>
      </Form>

      {user && !isSelf && !readOnly && (
        <>
          <Divider orientation="left" plain style={{ marginTop: 8 }}>
            This account
          </Divider>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
            These take effect straight away — they are not saved with the form, and Cancel does
            not undo them. Both end every session the account has open.
          </Typography.Paragraph>
          <Space wrap>
            <Popconfirm
              title={`Reset the password of ${user.username}?`}
              description="A new one-time password is generated and shown once. It also lifts a lockout."
              okText="Reset"
              onConfirm={() => reset.mutate()}
            >
              <Button icon={<KeyOutlined />} loading={reset.isPending}>
                Reset password
              </Button>
            </Popconfirm>
            <Popconfirm
              title={user.enabled ? `Disable ${user.username}?` : `Enable ${user.username}?`}
              description={
                user.enabled
                  ? 'They are logged out at once and cannot log in until enabled again. Nothing they wrote is removed.'
                  : 'They can log in again with their current password.'
              }
              okText={user.enabled ? 'Disable' : 'Enable'}
              okButtonProps={{ danger: user.enabled }}
              onConfirm={() => toggle.mutate()}
            >
              <Button
                danger={user.enabled}
                icon={user.enabled ? <StopOutlined /> : <CheckOutlined />}
                loading={toggle.isPending}
              >
                {user.enabled ? 'Disable' : 'Enable'}
              </Button>
            </Popconfirm>
          </Space>
        </>
      )}
    </Modal>
  );
}

/**
 * The one time a password is shown. Said plainly, because closing this without copying it
 * means resetting it again.
 */
export function IssuedPasswordModal({
  issued,
  onClose,
}: {
  issued: UserPasswordResponse | null;
  onClose: () => void;
}) {
  return (
    <Modal
      open={!!issued}
      title={issued ? `Account ${issued.user.username}` : ''}
      onCancel={onClose}
      footer={<Button type="primary" onClick={onClose}>Done</Button>}
    >
      {issued?.temporaryPassword ? (
        <>
          <Typography.Paragraph>
            Give them this password. It is shown only now — closing this window without copying
            it means resetting it again. They choose their own at the first login.
          </Typography.Paragraph>
          <Typography.Paragraph>
            <Typography.Text code copyable style={{ fontSize: 18 }}>
              {issued.temporaryPassword}
            </Typography.Text>
          </Typography.Paragraph>
        </>
      ) : (
        <Typography.Paragraph>
          Created with the password you typed. They choose their own at the first login.
        </Typography.Paragraph>
      )}
    </Modal>
  );
}
