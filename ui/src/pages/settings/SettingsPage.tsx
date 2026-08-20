import { useEffect } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
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
import { circulationsApi } from '../../api/circulations';
import { SendingTodayPanel } from '../../components/SendingToday';
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

  // Same query key the Circulars tab uses, so the two tabs share one cached answer and can
  // never quote different totals for the same day. Refetched on an interval rather than once:
  // this panel is read to decide whether there is room to send, and a figure from whenever the
  // tab happened to be opened is the wrong basis for that.
  const todayQ = useQuery({
    queryKey: ['circulations', 'today'],
    queryFn: circulationsApi.today,
    refetchInterval: 60_000,
  });

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

  // Saved on its own the moment it is ticked, rather than waiting for the form's Save.
  // The pacing fields below belong to whichever provider is in force, so the switch has to
  // land first and the form redraw with the new provider's numbers — a single Save would
  // otherwise write the mailbox's three-second gap against Brevo, or vice versa.
  const setProvider = useMutation({
    mutationFn: settingsApi.setProvider,
    onSuccess: (next) => {
      message.success(`Circulars will be sent via ${next.providerLabel}`);
      invalidate();
    },
  });

  const usingBrevo = settings?.provider === 'BREVO';
  const d = settings?.defaults;
  const defaultHint = (label: string, value: string | number) => (
    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
      {label}: {value}
    </Typography.Text>
  );

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <Card title="Sent today" loading={todayQ.isLoading}>
        <SendingTodayPanel today={todayQ.data} />
      </Card>

      <Card
        title={
          <Space>
            How circulars are sent
            <Tag color={usingBrevo ? 'purple' : 'blue'}>
              {settings?.providerLabel ?? '—'}
            </Tag>
          </Space>
        }
        loading={query.isLoading}
      >
        <Checkbox
          checked={!!usingBrevo}
          disabled={setProvider.isPending || query.isLoading}
          onChange={(e) => setProvider.mutate(e.target.checked)}
        >
          Use Brevo for circs
        </Checkbox>
        <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
          {usingBrevo ? (
            <>
              Circulars go out through Brevo's transactional API. Brevo owns the delivery, so
              the same list goes out in minutes rather than an hour and a bad address costs a
              bounce on the Brevo account rather than a strike against your own mailbox — but
              the mail no longer leaves your mailbox, so it will not appear in its Sent
              folder and replies come back only because of the From address. That address has
              to be verified as a sender in Brevo, or Brevo refuses the message.
            </>
          ) : (
            <>
              Circulars are sent one at a time from your own mailbox over SMTP, exactly as if
              you had written them by hand — best for landing in a broker's inbox, and replies
              go where you expect. The cost is that your mailbox's quota and reputation are
              what is being spent, which is why the pacing below is deliberately slow.
            </>
          )}
        </Typography.Paragraph>
        {usingBrevo && settings && !settings.brevoConfigured && (
          <Alert
            type="error"
            showIcon
            style={{ marginTop: 12 }}
            message="No Brevo API key"
            description={
              <>
                Brevo is selected but <Typography.Text code>BREVO_API_KEY</Typography.Text> is
                not set, so sending will fail on the first message. Put the key in{' '}
                <Typography.Text code>.env</Typography.Text> and restart the api container, or
                untick the box to go back to sending from your mailbox.
              </>
            }
          />
        )}
        {!usingBrevo && settings && settings.brevoConfigured && (
          <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
            A Brevo API key is configured, so the switch is ready whenever you want it.
          </Typography.Paragraph>
        )}
      </Card>

      <Card
        title={
          <Space>
            Circulations
            <Tag color={usingBrevo ? 'purple' : 'blue'}>
              {settings?.providerLabel ?? '—'}
            </Tag>
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
                    {usingBrevo
                      ? 'Must be verified as a sender in Brevo, or Brevo will refuse the message.'
                      : 'Must be the authenticated mailbox or one of its verified aliases, or the provider will refuse the message.'}
                    {d?.fromAddress ? ` Default: ${d.fromAddress}` : ''}
                  </Typography.Text>
                }
              >
                <Input placeholder="desk@example.com" />
              </Form.Item>
            </Col>
          </Row>

          {/* Kept on screen under Brevo rather than hidden: they are still what the mailbox
              flow will use the moment the box is unticked, and a field that vanishes is a
              field the user assumes was lost. */}
          <Row gutter={16}>
            <Col xs={24} md={12}>
              <Form.Item
                name="smtpHost"
                label="SMTP host"
                rules={[{ required: true, message: 'A host is required' }]}
                extra={
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {usingBrevo ? 'Unused while Brevo is selected. ' : ''}
                    {d ? `Default: ${d.smtpHost}` : ''}
                  </Typography.Text>
                }
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
                    {usingBrevo ? ' Unused while Brevo is selected.' : ''}
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
              <br />
              <br />
              These values belong to <b>{settings?.providerLabel ?? 'the current provider'}</b>{' '}
              and are stored separately from the other flow's. Ticking or unticking the box
              above swaps them, so each provider keeps its own tuning and starts from a
              baseline that suits it —{' '}
              {usingBrevo
                ? 'Brevo is built for bulk and only its daily plan allowance really binds, so its defaults are much faster.'
                : 'a personal mailbox can be suspended for exceeding its hourly cap, so its defaults are deliberately slow.'}{' '}
              Reset covers only the provider on screen.
            </>
          }
        />
      </Card>

      <Card title="Mail credentials">
        <Typography.Paragraph type="secondary">
          The mailbox login — <Typography.Text code>MAIL_USERNAME</Typography.Text> and{' '}
          <Typography.Text code>MAIL_PASSWORD</Typography.Text> — the Brevo key,{' '}
          <Typography.Text code>BREVO_API_KEY</Typography.Text>, and{' '}
          <Typography.Text code>MAIL_REPLY_TO</Typography.Text> all stay in{' '}
          <Typography.Text code>.env</Typography.Text> and are deliberately not editable here:
          these settings are stored in the database and served to the browser, which is the
          wrong place for a mailbox password or an API key with full send rights. Change them
          there and restart the api container. The From identity above is editable because it
          is not a secret — but the provider still checks it, against the authenticated
          mailbox under SMTP and against your verified senders under Brevo.
        </Typography.Paragraph>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          <Typography.Text code>MAIL_ENABLED</Typography.Text> is the master switch and covers
          both flows: left false, nothing is sent whichever box is ticked.
        </Typography.Paragraph>
      </Card>
    </Space>
  );
}
