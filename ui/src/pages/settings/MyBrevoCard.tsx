import { useEffect } from 'react';
import { Alert, App, Button, Card, Col, Form, Input, Popconfirm, Row, Space, Tag } from 'antd';
import { DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { myBrevoApi, type BrevoAccountRequest } from '../../api/myBrevo';

/**
 * Your own Brevo key: what your circulars go out through when the desk sends by Brevo.
 *
 * Per person, like the mailbox above it. A key is an allowance and a sending reputation, and
 * circulars are sent per person, so nobody spends a colleague's — or another desk's. The
 * server's own key belongs to whoever owns the server's mailbox and shows here for them
 * alone. The key is stored encrypted and never shown again, only its last four characters;
 * leaving the field empty on a later save keeps it.
 */
export default function MyBrevoCard() {
  const { message: toast } = App.useApp();
  const qc = useQueryClient();
  const [form] = Form.useForm<BrevoAccountRequest>();
  const query = useQuery({ queryKey: ['me', 'brevoAccount'], queryFn: myBrevoApi.get });
  const account = query.data;

  useEffect(() => {
    if (account) {
      form.setFieldsValue({
        apiKey: undefined,
        senderAddress: account.senderAddress,
        senderName: account.senderName,
      });
    }
  }, [account, form]);

  // The key decides "Brevo configured" on the Circulations card and the Brevo figures in
  // Sent today; the sender decides the From shown there.
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['me', 'brevoAccount'] });
    qc.invalidateQueries({ queryKey: ['settings'] });
    qc.invalidateQueries({ queryKey: ['campaign'] });
    qc.invalidateQueries({ queryKey: ['circulations', 'today'] });
  };

  const save = useMutation({
    mutationFn: (v: BrevoAccountRequest) => myBrevoApi.save({ ...v, apiKey: v.apiKey || undefined }),
    onSuccess: () => {
      toast.success('Brevo key saved');
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: myBrevoApi.remove,
    onSuccess: () => {
      toast.success('Brevo key removed');
      form.resetFields();
      refresh();
    },
  });

  const saved = account?.source === 'PERSONAL';
  const status =
    account?.source === 'PERSONAL' ? (
      <Tag color="green">key {account.keyHint}</Tag>
    ) : account?.source === 'SERVER' ? (
      <Tag color="blue">the server's key</Tag>
    ) : (
      <Tag>none</Tag>
    );

  return (
    <Card
      title={
        <Space>
          My Brevo account
          {status}
        </Space>
      }
      loading={query.isLoading}
      extra={
        <Space>
          {saved && (
            <Popconfirm
              title="Remove your Brevo key?"
              description="Circulars sent by Brevo stop going out for you until a key is saved again. History stays."
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
      }
    >
      {account && !account.canStore && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message="This server cannot store Brevo keys yet"
          description="An administrator has to set CREDENTIALS_KEY on the server first."
        />
      )}
      {account?.source === 'SERVER' && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="You are using the server's Brevo key"
          description="BREVO_API_KEY in the server's environment is yours, because the server's mailbox is. Saving a key of your own here replaces it for you."
        />
      )}
      <Form<BrevoAccountRequest>
        form={form}
        layout="vertical"
        onFinish={(v) => save.mutate(v)}
        disabled={!account?.canStore}
      >
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="apiKey"
              label="API key"
              extra={
                saved
                  ? 'Stored encrypted. Leave empty to keep the one saved.'
                  : "From Brevo's SMTP & API screen (a v3 key). Stored encrypted."
              }
              rules={saved ? [] : [{ required: true, message: 'Required' }]}
            >
              <Input.Password autoComplete="new-password" placeholder={saved ? account?.keyHint : undefined} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="senderAddress"
              label="Send as"
              extra="A sender verified in Brevo, or Brevo refuses the message. Empty sends as your mailbox address."
              rules={[{ type: 'email', message: 'Not an email address' }]}
            >
              <Input autoComplete="off" placeholder="desk@example.com" />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item name="senderName" label="Name to send as">
              <Input placeholder="As recipients should see it" />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Card>
  );
}
