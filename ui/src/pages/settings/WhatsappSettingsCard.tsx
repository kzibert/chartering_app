import { useEffect } from 'react';
import { App, Button, Card, Form, Input, Popconfirm, Space, Tag, Typography } from 'antd';
import { SaveOutlined, UndoOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { settingsApi } from '../../api/settings';

interface FormValues {
  message: string;
}

/**
 * The greeting prefilled into the WhatsApp links on phone contacts.
 *
 * <p>One field, because that is all there is to configure: the app never sends anything to
 * WhatsApp itself. Clicking a contact's WhatsApp button opens wa.me with this text already
 * typed into the chat, and the user presses send — or doesn't. That also makes it the text
 * used when checking whether a number is registered at all, since opening the chat is the
 * only check available.
 */
export default function WhatsappSettingsCard() {
  const { message: toast } = App.useApp();
  const qc = useQueryClient();
  const [form] = Form.useForm<FormValues>();

  const query = useQuery({ queryKey: ['settings', 'whatsapp'], queryFn: settingsApi.whatsapp });
  const settings = query.data;

  // Reset from the server's answer so a save or a reset leaves the field showing what is
  // actually stored, not what was typed.
  useEffect(() => {
    if (settings) form.setFieldsValue({ message: settings.message });
  }, [settings, form]);

  const invalidate = () => qc.invalidateQueries({ queryKey: ['settings', 'whatsapp'] });

  const save = useMutation({
    mutationFn: (v: FormValues) => settingsApi.updateWhatsapp({ message: v.message.trim() }),
    onSuccess: () => {
      toast.success('WhatsApp message saved');
      invalidate();
    },
  });

  const reset = useMutation({
    mutationFn: settingsApi.resetWhatsapp,
    onSuccess: () => {
      toast.success('Reset to the built-in default');
      invalidate();
    },
  });

  const placeholders = Object.entries(settings?.placeholders ?? {});

  return (
    <Card
      title={
        <Space>
          WhatsApp message
          {settings?.customised ? <Tag color="blue">customised</Tag> : <Tag>using default</Tag>}
        </Space>
      }
      loading={query.isLoading}
      extra={
        /* Wraps for the same reason as the Circulations card above it. */
        <Space wrap>
          <Popconfirm
            title="Reset to the built-in default?"
            description={`Goes back to "${settings?.defaultMessage ?? ''}".`}
            onConfirm={() => reset.mutate()}
            disabled={!settings?.customised}
          >
            <Button
              icon={<UndoOutlined />}
              loading={reset.isPending}
              disabled={!settings?.customised}
            >
              Reset to default
            </Button>
          </Popconfirm>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={save.isPending}
            onClick={() => form.submit()}
          >
            Save
          </Button>
        </Space>
      }
    >
      <Form<FormValues> form={form} layout="vertical" onFinish={(v) => save.mutate(v)}>
        <Form.Item
          name="message"
          label="Opening message"
          rules={[
            { required: true, message: 'A message is required' },
            { max: 500, message: '500 characters or fewer' },
          ]}
          extra={
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Prefilled into the chat, ready to send — nothing is sent by the app.
              {settings?.defaultMessage ? ` Default: "${settings.defaultMessage}"` : ''}
            </Typography.Text>
          }
        >
          <Input.TextArea rows={2} placeholder="Good day, {{greeting}}" />
        </Form.Item>
      </Form>

      <Typography.Paragraph type="secondary" style={{ marginBottom: 4 }}>
        Placeholders, filled in from the contact you clicked — the same ones a circular
        understands:
      </Typography.Paragraph>
      <ul style={{ marginTop: 0, marginBottom: 16, paddingLeft: 20 }}>
        {placeholders.map(([key, description]) => (
          <li key={key}>
            <Typography.Text code>{`{{${key}}}`}</Typography.Text>{' '}
            <Typography.Text type="secondary">— {description}</Typography.Text>
          </li>
        ))}
      </ul>

      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        Use it from the contact rows: with <b>Edit</b> on, a phone number gets a{' '}
        <b>WA?</b> button that opens the chat with this message already typed. WhatsApp has
        no way to be asked whether a number is registered, so the check is simply whether a
        chat comes up — say so, and the number keeps a WhatsApp link from then on. Numbers
        need their country code; one stored without it is flagged in the popup rather than
        silently mis-dialled.
      </Typography.Paragraph>
    </Card>
  );
}
