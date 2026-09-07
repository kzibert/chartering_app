import { useEffect } from 'react';
import {
  App,
  Alert,
  Button,
  Card,
  Col,
  Form,
  InputNumber,
  Popconfirm,
  Row,
  Space,
  Tag,
  Typography,
} from 'antd';
import { SaveOutlined, ThunderboltOutlined, UndoOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  useIntakeMutations,
  useIntakeStatus,
  useParserSettings,
  useSweep,
} from '../../intake/store';

interface FormValues {
  sweepIntervalMinutes: number;
  sweepBatchSize: number;
  sweepMaxAgeDays: number;
}

/**
 * How often incoming mail is read by the model, and how much of it at a time.
 *
 * <p><b>Here rather than in the environment</b>, unlike everything else about the parser.
 * Where the model lives and how long to wait for it are facts about a deployment and belong
 * in {@code .env}; how often to read is a knob turned while watching the queue — "that was
 * too slow this morning", "leave it alone, the GPU is training" — and an environment
 * variable is a redeploy. The same argument that put the circular provider in this table.
 *
 * <p><b>0 is a supported setting, not a broken one.</b> It stops the timer and leaves Parse
 * now as the only way in, which is what somebody wants while the workstation is doing
 * something else. The card says so rather than treating it as an error, and the button is
 * right here so turning the timer off does not mean navigating away to use it.
 *
 * <p>Absent when the feature is off, like the tab: a deployment that cannot reach a model
 * has nothing to schedule.
 */
export default function ParserSettingsCard() {
  const { message: toast } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const status = useIntakeStatus();
  const enabled = status.data?.enabled === true;
  const query = useParserSettings(enabled);
  const sweep = useSweep(enabled);
  const { updateSettings, resetSettings, startSweep } = useIntakeMutations();

  const settings = query.data;

  // Reset from the server's answer so a save or a reset leaves the fields showing what is
  // actually stored, not what was typed.
  useEffect(() => {
    if (settings) {
      form.setFieldsValue({
        sweepIntervalMinutes: settings.sweepIntervalMinutes,
        sweepBatchSize: settings.sweepBatchSize,
        sweepMaxAgeDays: settings.sweepMaxAgeDays,
      });
    }
  }, [settings, form]);

  if (!enabled) return null;

  const customised =
    settings != null &&
    (settings.sweepIntervalMinutes !== settings.defaultSweepIntervalMinutes ||
      settings.sweepBatchSize !== settings.defaultSweepBatchSize ||
      settings.sweepMaxAgeDays !== settings.defaultSweepMaxAgeDays);
  const running = sweep.data?.running === true;
  const reachable = status.data?.reachable === true;

  return (
    <Card
      title={
        <Space wrap>
          Reading the mail
          {customised ? <Tag color="blue">customised</Tag> : <Tag>using defaults</Tag>}
          <Tag color={reachable ? 'green' : 'red'}>
            {reachable ? 'Model server up' : 'Model server down'}
          </Tag>
        </Space>
      }
      loading={query.isLoading}
      extra={
        <Space wrap>
          <Popconfirm
            title="Back to the defaults?"
            description={`Every ${settings?.defaultSweepIntervalMinutes ?? 30} minutes, ${
              settings?.defaultSweepBatchSize ?? 20
            } messages a sweep, mail from the last ${
              settings?.defaultSweepMaxAgeDays ?? 30
            } days.`}
            onConfirm={() => resetSettings.mutate(undefined, {
              onSuccess: () => toast.success('Back to the defaults'),
            })}
            disabled={!customised}
          >
            <Button icon={<UndoOutlined />} loading={resetSettings.isPending} disabled={!customised}>
              Reset
            </Button>
          </Popconfirm>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={updateSettings.isPending}
            onClick={() => form.submit()}
          >
            Save
          </Button>
        </Space>
      }
    >
      {!reachable && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`The model is not answering at ${status.data?.modelUrl ?? 'the configured address'}.`}
          description="Start it in the chartering-ml project: make serve-docker. Until then a sweep will stop at the first message and record why."
        />
      )}

      <Form<FormValues>
        form={form}
        layout="vertical"
        onFinish={(v) =>
          updateSettings.mutate(v, { onSuccess: () => toast.success('Saved') })
        }
      >
        <Row gutter={16}>
          <Col xs={24} md={10}>
            <Form.Item
              name="sweepIntervalMinutes"
              label="Read the mail every"
              rules={[{ required: true, message: 'A number of minutes is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Minutes. <b>0 turns the timer off</b> — nothing is read until you press
                  Parse now, which is a fine way to run it.
                </Typography.Text>
              }
            >
              <InputNumber min={0} max={1440} step={5} addonAfter="min" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={7}>
            <Form.Item
              name="sweepBatchSize"
              label="Messages per sweep"
              rules={[{ required: true, message: 'A batch size is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  A ceiling, not a target. Reaching it is not an error — the next sweep
                  carries on where this one stopped.
                </Typography.Text>
              }
            >
              <InputNumber min={1} max={100} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={7}>
            <Form.Item
              name="sweepMaxAgeDays"
              label="Only read mail from the last"
              rules={[{ required: true, message: 'A number of days is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Days. <b>0 removes the limit</b> and lets a sweep reach back through the
                  whole mailbox. A position list from last year is not information — the ship
                  sailed — so reading it would fill Open fleet with rows that are wrong by
                  construction.
                </Typography.Text>
              }
            >
              <InputNumber min={0} max={3650} addonAfter="days" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
      </Form>

      <Space wrap>
        <Button
          icon={<ThunderboltOutlined />}
          loading={running || startSweep.isPending}
          disabled={running || !reachable}
          onClick={() => startSweep.mutate()}
        >
          {running ? 'Reading…' : 'Parse now'}
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {status.data?.unparsed ?? 0} message(s) waiting
          {(status.data?.sweepMaxAgeDays ?? 0) > 0
            ? ` in the last ${status.data?.sweepMaxAgeDays} days`
            : ''}
          {status.data?.lastSweepAt
            ? ` · last read ${dayjs(status.data.lastSweepAt).format('D MMM HH:mm')}`
            : ''}
          {status.data?.nextSweepAt
            ? ` · next about ${dayjs(status.data.nextSweepAt).format('HH:mm')}`
            : ''}
        </Typography.Text>
      </Space>

      <Typography.Paragraph type="secondary" style={{ marginTop: 16, marginBottom: 0 }}>
        A sweep hands each unread message to the model and files what comes back: open
        positions onto the Open fleet tab, cargoes onto the Cargoes tab. Anything it will not
        decide alone — a particular that disagrees with a vessel's record, a hull nobody has
        heard of, a cargo that looks like one already in hand — waits on the <b>Intake</b> tab.
        Where the model lives and how long to wait for it are set in <code>.env</code>, not
        here.
      </Typography.Paragraph>
    </Card>
  );
}
