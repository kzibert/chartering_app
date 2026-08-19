import { useEffect } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Row,
  Space,
  Tag,
  Typography,
} from 'antd';
import { SaveOutlined, UndoOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { settingsApi } from '../../api/settings';
import type { CirculationSettings, CirculationSettingsRequest } from '../../api/types';

/**
 * Delays are stored in milliseconds but shown in seconds — nobody reasons about a send
 * cadence in milliseconds, and the half-second step keeps the stored value expressible.
 */
const toSeconds = (ms: number) => Math.round(ms / 100) / 10;
const toMillis = (s: number) => Math.round(s * 1000);

/** The pause between runs is an hours-and-minutes affair, so it is typed in minutes. */
const toMinutes = (ms: number) => Math.round(ms / 6000) / 10;
const minutesToMillis = (m: number) => Math.round(m * 60000);

interface FormValues {
  fromAddress: string;
  fromName: string;
  smtpHost: string;
  smtpPort: number;
  minDelaySeconds: number;
  maxDelaySeconds: number;
  maxRecipientsPerCampaign: number;
  batchPauseMinutes: number;
}

const toForm = (s: CirculationSettingsRequest): FormValues => ({
  fromAddress: s.fromAddress,
  fromName: s.fromName ?? '',
  smtpHost: s.smtpHost,
  smtpPort: s.smtpPort,
  minDelaySeconds: toSeconds(s.minDelayMs),
  maxDelaySeconds: toSeconds(s.maxDelayMs),
  maxRecipientsPerCampaign: s.maxRecipientsPerCampaign,
  batchPauseMinutes: toMinutes(s.batchPauseMs),
});

export default function SettingsPage() {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [form] = Form.useForm<FormValues>();

  const query = useQuery({ queryKey: ['settings', 'circulation'], queryFn: settingsApi.circulation });
  const settings = query.data;

  // Reset the form whenever the server's answer changes, so a save or a reset leaves the
  // fields showing what is actually stored rather than what was typed.
  useEffect(() => {
    if (settings) form.setFieldsValue(toForm(settings));
  }, [settings, form]);

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['settings'] });
    // The Circulars tab prints these values in its header chips and uses the cap to decide
    // whether Send is allowed, so it has to be told.
    qc.invalidateQueries({ queryKey: ['campaign', 'config'] });
  };

  const save = useMutation({
    mutationFn: (v: FormValues) =>
      settingsApi.update({
        fromAddress: v.fromAddress.trim(),
        fromName: v.fromName?.trim() ?? '',
        smtpHost: v.smtpHost.trim(),
        smtpPort: v.smtpPort,
        minDelayMs: toMillis(v.minDelaySeconds),
        maxDelayMs: toMillis(v.maxDelaySeconds),
        maxRecipientsPerCampaign: v.maxRecipientsPerCampaign,
        batchPauseMs: minutesToMillis(v.batchPauseMinutes),
      }),
    onSuccess: () => {
      message.success('Circulation settings saved');
      invalidate();
    },
  });

  const reset = useMutation({
    mutationFn: settingsApi.reset,
    onSuccess: () => {
      message.success('Reset to the configured defaults');
      invalidate();
    },
  });

  const d = settings?.defaults;
  const defaultHint = (label: string, value: string | number) => (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {label}: {value}
    </Typography.Text>
  );

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            Circulations
            {settings?.customised ? (
              <Tag color="blue">customised</Tag>
            ) : (
              <Tag>using defaults</Tag>
            )}
          </Space>
        }
        loading={query.isLoading}
        extra={
          <Space>
            <Popconfirm
              title="Reset to the configured defaults?"
              description="Drops your changes and goes back to the values from .env."
              onConfirm={() => reset.mutate()}
              disabled={!settings?.customised}
            >
              <Button icon={<UndoOutlined />} loading={reset.isPending} disabled={!settings?.customised}>
                Reset to defaults
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
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="fromName"
                label="From name"
                extra={d && defaultHint('Default', d.fromName || '(none)')}
              >
                <Input placeholder="Maritella Chartering Desk" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="fromAddress"
                label="From address"
                rules={[
                  { required: true, message: 'A From address is required' },
                  { type: 'email', message: 'Not a valid email address' },
                ]}
                extra={
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    Must be the authenticated mailbox or one of its verified aliases, or the
                    provider will refuse the message.
                    {d?.fromAddress ? ` Default: ${d.fromAddress}` : ''}
                  </Typography.Text>
                }
              >
                <Input placeholder="desk@example.com" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="smtpHost"
                label="SMTP host"
                rules={[{ required: true, message: 'A host is required' }]}
                extra={d && defaultHint('Default', d.smtpHost)}
              >
                <Input placeholder="smtp.zoho.eu" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="smtpPort"
                label="SMTP port"
                rules={[{ required: true, message: 'A port is required' }]}
                extra={
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    465 uses implicit SSL, anything else uses STARTTLS.
                    {d ? ` Default: ${d.smtpPort}` : ''}
                  </Typography.Text>
                }
              >
                <InputNumber style={{ width: '100%' }} min={1} max={65535} />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={16}>
            <Col xs={12} md={6}>
              <Form.Item
                name="minDelaySeconds"
                label="Shortest gap (s)"
                rules={[{ required: true, message: 'Required' }]}
                extra={d && defaultHint('Default', toSeconds(d.minDelayMs))}
              >
                <InputNumber style={{ width: '100%' }} min={0} max={3600} step={0.5} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="maxDelaySeconds"
                label="Longest gap (s)"
                dependencies={['minDelaySeconds']}
                rules={[
                  { required: true, message: 'Required' },
                  // Checked here as well as on the server: a max below the min would make
                  // the random gap meaningless, and the sender would clamp it silently.
                  ({ getFieldValue }) => ({
                    validator: (_, value) =>
                      value == null || value >= getFieldValue('minDelaySeconds')
                        ? Promise.resolve()
                        : Promise.reject(new Error('Must be at least the shortest gap')),
                  }),
                ]}
                extra={d && defaultHint('Default', toSeconds(d.maxDelayMs))}
              >
                <InputNumber style={{ width: '100%' }} min={0} max={3600} step={0.5} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="maxRecipientsPerCampaign"
                label="Max recipients per run"
                rules={[{ required: true, message: 'Required' }]}
                extra={d && defaultHint('Default', d.maxRecipientsPerCampaign)}
              >
                <InputNumber style={{ width: '100%' }} min={1} max={100000} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="batchPauseMinutes"
                label="Pause between runs (min)"
                rules={[{ required: true, message: 'Required' }]}
                extra={d && defaultHint('Default', toMinutes(d.batchPauseMs))}
              >
                <InputNumber style={{ width: '100%' }} min={0} max={1440} step={5} />
              </Form.Item>
            </Col>
          </Row>
        </Form>

        <Alert
          type="info"
          showIcon
          message="How these are used"
          description={
            <>
              The gap between two messages is drawn at random from the range above — never a
              fixed interval, because a regular cadence is the classic bulk-sender fingerprint.
              A circulation with more recipients than the per-run cap is not refused: it is
              sent as several runs of that size with the pause above between them, which keeps
              each burst inside the provider's per-hour allowance. It does not fit a
              circulation inside a <b>daily</b> limit — 300 messages are 300 messages however
              they are spaced, so check the plan before sending a list that large. Changes
              apply to the next circulation you start; one already sending keeps the pacing
              and run size it began with.
            </>
          }
        />
      </Card>

      <Card title="Mail credentials">
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          The mailbox login — <Typography.Text code>MAIL_USERNAME</Typography.Text> and{' '}
          <Typography.Text code>MAIL_PASSWORD</Typography.Text> — plus{' '}
          <Typography.Text code>MAIL_REPLY_TO</Typography.Text> stay in{' '}
          <Typography.Text code>.env</Typography.Text> and are deliberately not editable here:
          these settings are stored in the database and served to the browser, which is the
          wrong place for a mailbox password. Change them there and restart the api container.
          The From identity above is editable because it is not a secret — but the provider
          still checks it against the authenticated account.
        </Typography.Paragraph>
      </Card>
    </Space>
  );
}
