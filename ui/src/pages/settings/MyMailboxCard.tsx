import { useEffect } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Col,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Row,
  Space,
  Tag,
} from 'antd';
import { DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { myMailboxApi, type MailAccountRequest } from '../../api/myMailbox';

/**
 * Your own mailbox: the one the Mailbox tab reads, replies go out from, and circulars are sent
 * through when they go by SMTP.
 *
 * Everybody has their own. The server's mailbox — configured in its environment — belongs to
 * one account, and that person sees it here read-only; everybody else saves theirs. The
 * password is stored encrypted and never shown again; leaving the field empty on a later save
 * keeps it.
 */
export default function MyMailboxCard() {
  const { message: toast } = App.useApp();
  const qc = useQueryClient();
  const [form] = Form.useForm<MailAccountRequest>();
  const query = useQuery({ queryKey: ['me', 'mailAccount'], queryFn: myMailboxApi.get });
  const account = query.data;

  useEffect(() => {
    if (account?.source === 'PERSONAL') {
      form.setFieldsValue({
        emailAddress: account.emailAddress,
        displayName: account.displayName,
        imapHost: account.imapHost,
        imapPort: account.imapPort,
        imapSsl: account.imapSsl,
        smtpHost: account.smtpHost,
        smtpPort: account.smtpPort,
        enabled: account.enabled,
        password: undefined,
      });
    }
  }, [account, form]);

  // Everything the mailbox decides is somewhere else on screen too.
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['me', 'mailAccount'] });
    qc.invalidateQueries({ queryKey: ['mailbox'] });
    qc.invalidateQueries({ queryKey: ['campaign'] });
  };

  const save = useMutation({
    mutationFn: (v: MailAccountRequest) => myMailboxApi.save({ ...v, password: v.password || undefined }),
    onSuccess: () => {
      toast.success('Mailbox saved');
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: myMailboxApi.remove,
    onSuccess: () => {
      toast.success('Mailbox removed');
      form.resetFields();
      refresh();
    },
  });

  const status =
    account?.source === 'SERVER' ? (
      <Tag color="blue">the server's mailbox</Tag>
    ) : account?.source === 'PERSONAL' ? (
      account.enabled ? <Tag color="green">in use</Tag> : <Tag>switched off</Tag>
    ) : (
      <Tag>none</Tag>
    );

  return (
    <Card
      title={
        <Space>
          My mailbox
          {status}
        </Space>
      }
      loading={query.isLoading}
      extra={
        account?.source !== 'SERVER' && (
          <Space>
            {account?.source === 'PERSONAL' && (
              <Popconfirm
                title="Remove your mailbox?"
                description="It stops being read and nothing more goes out from it. Mail already synced stays on the Mailbox tab."
                okText="Remove"
                okButtonProps={{ danger: true }}
                onConfirm={() => remove.mutate()}
              >
                <Button danger icon={<DeleteOutlined />} loading={remove.isPending}>
                  Remove
                </Button>
              </Popconfirm>
            )}
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={save.isPending}
              disabled={!account?.canStore}
              onClick={() => form.submit()}
            >
              Save
            </Button>
          </Space>
        )
      }
    >
      {account?.source === 'SERVER' ? (
        <>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="Your mailbox is the server's own"
            description="It is configured in the server's environment (IMAP_* and MAIL_*), so it is changed there rather than here."
          />
          <Descriptions size="small" column={1}>
            <Descriptions.Item label="Address">{account.emailAddress ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="IMAP">
              {account.imapHost}:{account.imapPort}
            </Descriptions.Item>
          </Descriptions>
        </>
      ) : (
        <>
          {account && !account.canStore && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="This server cannot store mailbox passwords yet"
              description="An administrator has to set CREDENTIALS_KEY on the server first."
            />
          )}
          <Form<MailAccountRequest>
            form={form}
            layout="vertical"
            initialValues={{ imapPort: 993, imapSsl: true, smtpPort: 465, enabled: true }}
            onFinish={(v) => save.mutate(v)}
            disabled={!account?.canStore}
          >
            <Row gutter={16}>
              <Col xs={24} md={12}>
                <Form.Item
                  name="emailAddress"
                  label="Email address"
                  extra="Also the login, and the From of everything you send."
                  rules={[
                    { required: true, message: 'The address is required' },
                    { type: 'email', message: 'Not an email address' },
                  ]}
                >
                  <Input autoComplete="off" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="displayName" label="Name to send as">
                  <Input placeholder="As recipients should see it" />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="imapHost" label="IMAP server" rules={[{ required: true, message: 'Required' }]}>
                  <Input placeholder="imap.zoho.eu" />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item name="imapPort" label="IMAP port" rules={[{ required: true, message: 'Required' }]}>
                  <InputNumber min={1} max={65535} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item name="imapSsl" label="IMAP over SSL" valuePropName="checked">
                  <Checkbox />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item name="smtpHost" label="SMTP server" rules={[{ required: true, message: 'Required' }]}>
                  <Input placeholder="smtp.zoho.eu" />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item
                  name="smtpPort"
                  label="SMTP port"
                  extra="465 is SSL, anything else STARTTLS"
                  rules={[{ required: true, message: 'Required' }]}
                >
                  <InputNumber min={1} max={65535} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item name="enabled" label="In use" valuePropName="checked">
                  <Checkbox />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item
                  name="password"
                  label="Password"
                  extra={
                    account?.source === 'PERSONAL'
                      ? 'Stored encrypted. Leave empty to keep the one saved.'
                      : 'Stored encrypted. With two-factor sign-in, use an app password.'
                  }
                  rules={account?.source === 'PERSONAL' ? [] : [{ required: true, message: 'Required' }]}
                >
                  <Input.Password autoComplete="new-password" />
                </Form.Item>
              </Col>
            </Row>
          </Form>
        </>
      )}
    </Card>
  );
}
