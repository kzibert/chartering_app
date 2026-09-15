import { useEffect } from 'react';
import { App, Button, Card, Form, Select, Space, Tag, Typography } from 'antd';
import { SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { settingsApi } from '../../api/settings';

interface FormValues {
  addresses: string[];
}

/** Close enough to catch a typo; the API applies the same test and names what it refuses. */
const EMAIL = /^[^@\s,]+@[^@\s.]+(\.[^@\s.]+)+$/;

/**
 * The desk's own email addresses.
 *
 * <p>The mail sweep reads the Sent folder as well as the inbox, so a reply of ours that quotes
 * a broker's cargo is read and recorded as another arrival of it — and the cargo then says it
 * was last sent by us, today. Listing the addresses here takes those arrivals out of a cargo's
 * senders and its last-sent date. Nothing is deleted: the rows stay, and taking an address off
 * the list brings them back.
 *
 * <p>No reset, because there is no default to go back to: an empty list is the default.
 */
export default function OwnAddressesCard() {
  const { message: toast } = App.useApp();
  const qc = useQueryClient();
  const [form] = Form.useForm<FormValues>();

  const query = useQuery({ queryKey: ['settings', 'ownAddresses'], queryFn: settingsApi.ownAddresses });
  const settings = query.data;

  // From the server's answer, so the tags show the lower-cased, de-duplicated list stored.
  useEffect(() => {
    if (settings) form.setFieldsValue({ addresses: settings.addresses });
  }, [settings, form]);

  const save = useMutation({
    mutationFn: (v: FormValues) =>
      settingsApi.updateOwnAddresses({ addresses: (v.addresses ?? []).map((a) => a.trim()) }),
    onSuccess: () => {
      toast.success('Email addresses saved');
      qc.invalidateQueries({ queryKey: ['settings', 'ownAddresses'] });
      // What the list changes is on the Cargoes tab: its senders, last-sent dates and the
      // drawer's arrivals, which the intake store caches.
      qc.invalidateQueries({ queryKey: ['cargoes'] });
      qc.invalidateQueries({ queryKey: ['cargo'] });
      qc.invalidateQueries({ queryKey: ['intake'] });
    },
  });

  const count = settings?.addresses.length ?? 0;

  return (
    <Card
      title={
        <Space>
          My email addresses
          {count > 0 ? <Tag color="blue">{count}</Tag> : <Tag>none set</Tag>}
        </Space>
      }
      loading={query.isLoading}
      extra={
        <Button type="primary" icon={<SaveOutlined />} loading={save.isPending} onClick={() => form.submit()}>
          Save
        </Button>
      }
    >
      <Form<FormValues> form={form} layout="vertical" onFinish={(v) => save.mutate(v)}>
        <Form.Item
          name="addresses"
          label="Addresses the desk writes from"
          rules={[
            {
              validator: (_, value?: string[]) => {
                const bad = (value ?? []).find((a) => !EMAIL.test(a.trim()));
                return bad ? Promise.reject(new Error(`"${bad}" is not an email address`)) : Promise.resolve();
              },
            },
          ]}
          extra={
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Type an address and press Enter, or paste several separated by commas or spaces.
            </Typography.Text>
          }
        >
          <Select
            mode="tags"
            open={false}
            suffixIcon={null}
            tokenSeparators={[',', ';', ' ']}
            placeholder="desk@example.com, chartering@example.com"
          />
        </Form.Item>
      </Form>

      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        The mail sweep reads your sent mail as well as the inbox, so a reply of yours that
        quotes a cargo is recorded as another arrival of it. Emails from these addresses are
        left out of a cargo's <b>Sent by</b> list and its <b>Last sent</b> date. Nothing is
        deleted — take an address off the list and those arrivals show again.
      </Typography.Paragraph>
    </Card>
  );
}
