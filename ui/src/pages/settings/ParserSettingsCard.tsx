import { useEffect, useState } from 'react';
import {
  App,
  Alert,
  Button,
  Card,
  Col,
  Divider,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Row,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  SaveOutlined,
  ThunderboltOutlined,
  UndoOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import {
  useIntakeMutations,
  useIntakeStatus,
  useParserSettings,
  useSweep,
} from '../../intake/store';
import { useCapabilities, useIntakeOn } from '../../capabilities/store';
import { readsWithCloud, useParserRoute } from '../../parser/route';
import { useFeedMutations, useFeedSettings } from '../../feed/store';

interface FormValues {
  sweepIntervalMinutes: number;
  sweepBatchSize: number;
  sweepMaxAgeDays: number;
  modelUrl: string;
  modelName: string;
  cloudUrl: string;
  cloudModel: string;
  fewShotExamples: number;
  fewShotMaxChars: number;
}

/**
 * How often incoming mail is read by the model, and how much of it at a time.
 *
 * <p><b>Here rather than in the environment</b>, unlike the timeouts. How often to read is a
 * knob turned while watching the queue — "that was too slow this morning", "leave it alone, the
 * GPU is training" — and an environment variable is a redeploy. The same argument that put the
 * circular provider in this table.
 *
 * <p><b>Which model reads it is the same kind of knob</b>, which is why the address is on this
 * card too. {@code PARSER_URL} is still what a fresh install uses and what clearing the field
 * restores; overriding it here is for the ordinary week — the extraction server and the Feed's
 * general model are swapped on one 8GB card, and a second box gets a port of its own. How long
 * to wait for whichever one is up stays in {@code .env}, because a timeout is a fact about a
 * deployment.
 *
 * <p><b>0 is a supported setting, not a broken one.</b> It stops the timer and leaves Parse
 * now as the only way in, which is what somebody wants while the workstation is doing
 * something else. The card says so rather than treating it as an error, and the button is
 * right here so turning the timer off does not mean navigating away to use it.
 *
 * <p>Absent when Intake does not exist for this person, like the tab. The cloud key is not
 * here: it is the person's own and lives in their browser ({@code ParserRouteCard}).
 */
export default function ParserSettingsCard() {
  const { message: toast } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const enabled = useIntakeOn();
  const caps = useCapabilities().data;
  const local = caps?.parserLocal === true;
  const route = useParserRoute();
  const status = useIntakeStatus({ enabled });
  const query = useParserSettings(enabled);
  const sweep = useSweep(enabled);
  const { updateSettings, resetSettings, startSweep } = useIntakeMutations();

  const settings = query.data;
  // Which model reads this person's mail: the cloud one when their browser holds a key and they
  // chose it (Settings > Your parser), else the office one.
  const cloud = readsWithCloud(route);

  // Reset from the server's answer so a save or a reset leaves the fields showing what is
  // actually stored, not what was typed.
  useEffect(() => {
    if (settings) {
      form.setFieldsValue({
        sweepIntervalMinutes: settings.sweepIntervalMinutes,
        sweepBatchSize: settings.sweepBatchSize,
        sweepMaxAgeDays: settings.sweepMaxAgeDays,
        // The address in force rather than the override, so the field always shows where mail is
        // actually being sent. Saving it unchanged stores nothing: a value equal to the
        // configured one is deleted, which leaves .env still owning it.
        modelUrl: settings.modelUrl,
        modelName: settings.modelName,
        cloudUrl: settings.cloudUrl,
        cloudModel: settings.cloudModel,
        fewShotExamples: settings.fewShotExamples,
        fewShotMaxChars: settings.fewShotMaxChars,
      });
    }
  }, [settings, form]);

  if (!enabled) return null;

  const customised =
    settings != null &&
    (settings.sweepIntervalMinutes !== settings.defaultSweepIntervalMinutes ||
      settings.sweepBatchSize !== settings.defaultSweepBatchSize ||
      settings.sweepMaxAgeDays !== settings.defaultSweepMaxAgeDays ||
      settings.fewShotExamples !== settings.defaultFewShotExamples ||
      settings.fewShotMaxChars !== settings.defaultFewShotMaxChars ||
      settings.modelUrlCustomised ||
      settings.modelNameCustomised ||
      settings.cloudUrlCustomised ||
      settings.cloudModelCustomised);
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
            } days, the model at ${settings?.defaultModelUrl ?? 'the configured address'}, the cloud model at ${
              settings?.defaultCloudUrl ?? 'its default address'
            }, and no retrieved examples.`}
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
          message={
            cloud
              ? 'The cloud model is not answering.'
              : `The model is not answering at ${status.data?.modelUrl ?? 'the configured address'}.`
          }
          description={
            cloud
              ? 'Check the cloud address, model name and key below.'
              : 'Start it in the chartering-ml project: make serve-docker. Until then a sweep will stop at the first message and record why.'
          }
        />
      )}

      <Form<FormValues>
        form={form}
        layout="vertical"
        onFinish={(v) => {
          updateSettings.mutate(v, { onSuccess: () => toast.success('Saved') });
        }}
      >
        <Row gutter={16}>
          <Col xs={24} md={10}>
            <Form.Item
              name="sweepIntervalMinutes"
              label="Read the mail every"
              rules={[{ required: true, message: 'A number of minutes is required' }]}
              extra={
                !local ? (
                  // Still shown and still saved: the office deployment reads on this same
                  // setting, and the two share one row.
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    Not used on this deployment, which has no timer: it reads only when asked —
                    Parse now, Parse everything, or messages picked on the Mailbox tab.
                  </Typography.Text>
                ) : (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    Minutes. <b>0 turns the timer off</b> — nothing is read until you press
                    Parse now, which is a fine way to run it.
                  </Typography.Text>
                )
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

        {local && <BoardFetchInterval />}

        {local && (<>
        <Divider style={{ marginTop: 0 }} orientation="left" plain>
          <Space size={4} wrap>
            Which model reads it
            {settings?.modelUrlCustomised || settings?.modelNameCustomised ? (
              <Tag color="blue">set here</Tag>
            ) : (
              <Tag>from .env</Tag>
            )}
          </Space>
        </Divider>

        <Row gutter={16}>
          <Col xs={24} md={16}>
            <Form.Item
              name="modelUrl"
              label="Model address"
              rules={[
                {
                  validator: (_, value: string) =>
                    !value || /^https?:\/\/\S+$/.test(value.trim())
                      ? Promise.resolve()
                      : Promise.reject(new Error('An http:// or https:// address, or empty for the configured one')),
                },
              ]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  The chat-completions endpoint that reads the mail. <b>Clear it</b> to go back to{' '}
                  <code>{settings?.defaultModelUrl}</code> from <code>.env</code>. The Feed
                  summarises through its own address, below — the two models are swapped on one
                  card and must not be one setting.
                </Typography.Text>
              }
            >
              <Input
                placeholder={settings?.defaultModelUrl}
                allowClear
                autoComplete="off"
                spellCheck={false}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="modelName"
              label="Model name"
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Sent only when set. llama-server serves one model and ignores it; an Ollama
                  refuses the request without it, so pointing at one is this and the address —
                  though it scores markedly worse on the same weights.
                </Typography.Text>
              }
            >
              <Input placeholder="none" allowClear autoComplete="off" spellCheck={false} />
            </Form.Item>
          </Col>
        </Row>
        </>)}

        <Divider style={{ marginTop: 0 }} orientation="left" plain>
          <Space size={4} wrap>
            Cloud model
            {cloud ? <Tag color="blue">reads your mail</Tag> : <Tag>not chosen</Tag>}
          </Space>
        </Divider>

        <Typography.Paragraph type="secondary" style={{ marginTop: -8, marginBottom: 16 }}>
          The address and model a cloud read goes to, for everybody on the installation. Each
          person reads with their own API key, saved under Your parser and kept in their browser.
        </Typography.Paragraph>

        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="cloudUrl"
              label="Cloud address"
              rules={[
                {
                  validator: (_, value: string) =>
                    !value || /^https?:\/\/\S+$/.test(value.trim())
                      ? Promise.resolve()
                      : Promise.reject(new Error('An http:// or https:// address, or empty for the default one')),
                },
              ]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  An OpenAI-compatible chat-completions endpoint. <b>Clear it</b> to go back to{' '}
                  <code>{settings?.defaultCloudUrl}</code>.
                </Typography.Text>
              }
            >
              <Input
                placeholder={settings?.defaultCloudUrl}
                allowClear
                autoComplete="off"
                spellCheck={false}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="cloudModel"
              label="Cloud model name"
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Sent with every request. <b>Clear it</b> to go back to{' '}
                  <code>{settings?.defaultCloudModel}</code>.
                </Typography.Text>
              }
            >
              <Input
                placeholder={settings?.defaultCloudModel}
                allowClear
                autoComplete="off"
                spellCheck={false}
              />
            </Form.Item>
          </Col>
        </Row>


        {caps?.retrieval && (<>
        <Divider style={{ marginTop: 0 }} orientation="left" plain>
          <Space size={4} wrap>
            Retrieved examples
            {settings && settings.fewShotExamples > 0 ? (
              <Tag color="blue">{settings.fewShotExamples} per email</Tag>
            ) : (
              <Tag>off</Tag>
            )}
          </Space>
        </Divider>

        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="fewShotExamples"
              label="Examples per email"
              rules={[{ required: true, message: 'A number from 0 to 8 is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  0 = off. When on, the parser is shown this many of the most similar labelled
                  emails from the Analysis corpus before each email. Two was scored against none
                  on held-out emails and read cargoes and unseen senders' vessels better; another
                  number has not been scored. Default <b>{settings?.defaultFewShotExamples ?? 2}</b>.
                </Typography.Text>
              }
            >
              <InputNumber min={0} max={8} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="fewShotMaxChars"
              label="Example budget (characters)"
              rules={[{ required: true, message: 'A budget of characters is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Examples are added nearest-first while they fit; an example is never cut short.
                  Default <b>{settings?.defaultFewShotMaxChars ?? 0}</b>.
                </Typography.Text>
              }
            >
              <InputNumber min={1000} max={60000} step={1000} addonAfter="chars" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        </>)}
      </Form>

      <Space wrap>
        <Button
          icon={<ThunderboltOutlined />}
          loading={running || startSweep.isPending}
          disabled={running || !reachable}
          onClick={() => startSweep.mutate(false)}
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
        Whether a local and a cloud model exist at all (<code>PARSER_ENABLED</code>,{' '}
        <code>PARSER_CLOUD_ENABLED</code>) and how long to wait for an answer are set in{' '}
        <code>.env</code>, not here.
      </Typography.Paragraph>
    </Card>
  );
}

/**
 * How often the boards a desk reads into Intake are fetched.
 *
 * Here because the Feed is switched off, and its settings card with it: the interval is still a
 * Feed setting on the server (FeedSettings, one installation row), and the timer still runs on
 * the office deployment for the boards Intake reads. Its own small save, so it never rides on
 * the parser's form.
 */
function BoardFetchInterval() {
  const { message: toast } = App.useApp();
  const settings = useFeedSettings().data;
  const { updateSettings } = useFeedMutations();
  const [minutes, setMinutes] = useState<number | null>(null);

  useEffect(() => {
    if (settings) setMinutes(settings.fetchIntervalMinutes);
  }, [settings]);

  if (!settings) return null;
  return (
    <Form.Item
      label="Fetch the boards read into Intake every"
      extra={
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          Minutes. <b>0 turns it off</b>; the Fetch button on the Intake tab still works. Only the
          boards a desk reads in are fetched while the Feed is off.
        </Typography.Text>
      }
    >
      <Space.Compact>
        <InputNumber
          min={0}
          max={1440}
          step={15}
          value={minutes}
          onChange={(v) => setMinutes(v)}
          addonAfter="min"
        />
        <Button
          loading={updateSettings.isPending}
          disabled={minutes == null || minutes === settings.fetchIntervalMinutes}
          onClick={() =>
            updateSettings.mutate(
              { fetchIntervalMinutes: minutes ?? 0 },
              { onSuccess: () => toast.success('Saved') },
            )
          }
        >
          Save
        </Button>
      </Space.Compact>
    </Form.Item>
  );
}
