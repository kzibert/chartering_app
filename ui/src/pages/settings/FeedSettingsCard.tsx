import { useEffect, useState, type ReactNode } from 'react';
import {
  Alert,
  App,
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
import { AimOutlined, SaveOutlined, UndoOutlined } from '@ant-design/icons';
import { feedApi } from '../../api/feed';
import { useFeedMutations, useFeedSettings, useFeedStatus } from '../../feed/store';

interface FormValues {
  contextWindowTokens: number;
  summaryMaxTokens: number;
  notesMaxTokens: number;
  lookbackDays: number;
  maxCallsPerTopic: number;
  fetchIntervalMinutes: number;
  modelUrl: string;
  modelName: string;
}

const hint = (text: ReactNode) => (
  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
    {text}
  </Typography.Text>
);

/**
 * How the Feed fits what it collected into the model, and how often it collects.
 *
 * <p>The context window is the one number everything else is measured against: prompt, material
 * and answer all share it. The default is what the model is served with today; Detect asks the
 * server, so raising --ctx-size in chartering-ml is one click here rather than a guess.
 *
 * <p><b>Which model writes them is its own address, not the parser's.</b> That was measured
 * rather than preferred: the extraction finetune reported "no vessel openings" for a ship.gr
 * position list full of them and invented eight Danube–Med rates. An 8GB card cannot hold both
 * servers, so they are swapped — which is exactly why the address is here and not in
 * <code>.env</code>. Clearing the field falls back to <code>FEED_LLM_URL</code>, and to the
 * parser's endpoint where that is blank too: the single-server shape, which works and is not the
 * one to want.
 *
 * <p>Shown only where the Feed can fetch and summarise — on the hosted instance there is no model
 * to size and nothing to schedule. The prompts are edited on the Feed tab, beside the topics
 * they are written for.
 */
export default function FeedSettingsCard() {
  const { message: toast } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const status = useFeedStatus();
  const enabled = status.data?.analysisEnabled === true;
  const query = useFeedSettings();
  const { updateSettings, resetSettings } = useFeedMutations();
  const [detecting, setDetecting] = useState(false);
  const settings = query.data;

  useEffect(() => {
    if (settings) {
      form.setFieldsValue({
        contextWindowTokens: settings.contextWindowTokens,
        summaryMaxTokens: settings.summaryMaxTokens,
        notesMaxTokens: settings.notesMaxTokens,
        lookbackDays: settings.lookbackDays,
        maxCallsPerTopic: settings.maxCallsPerTopic,
        fetchIntervalMinutes: settings.fetchIntervalMinutes,
        // The address in force, not the override: saving it back unchanged stores nothing.
        modelUrl: settings.modelUrl,
        modelName: settings.modelName,
      });
    }
  }, [settings, form]);

  if (!enabled) return null;

  const customised =
    settings != null &&
    (settings.contextWindowTokens !== settings.defaultContextWindowTokens ||
      settings.summaryMaxTokens !== settings.defaultSummaryMaxTokens ||
      settings.notesMaxTokens !== settings.defaultNotesMaxTokens ||
      settings.lookbackDays !== settings.defaultLookbackDays ||
      settings.maxCallsPerTopic !== settings.defaultMaxCallsPerTopic ||
      settings.fetchIntervalMinutes !== settings.defaultFetchIntervalMinutes ||
      settings.modelUrlCustomised ||
      settings.modelNameCustomised);

  const detect = async () => {
    setDetecting(true);
    try {
      const { contextWindowTokens } = await feedApi.detectContext();
      form.setFieldValue('contextWindowTokens', contextWindowTokens);
      toast.info(`The model reports ${contextWindowTokens.toLocaleString()} tokens. Save to use it.`);
    } catch {
      // The client's interceptor has already said why.
    } finally {
      setDetecting(false);
    }
  };

  return (
    <Card
      title={
        <Space wrap>
          Feed: model window and fetching
          {customised ? <Tag color="blue">customised</Tag> : <Tag>using defaults</Tag>}
          <Tag color={status.data?.reachable ? 'green' : 'red'}>
            {status.data?.reachable ? 'Model server up' : 'Model server down'}
          </Tag>
        </Space>
      }
      loading={query.isLoading}
      extra={
        <Space wrap>
          <Popconfirm
            title="Back to the defaults?"
            description="The numbers and the model address. The prompts are kept; they have their own reset on the Feed tab."
            onConfirm={() =>
              resetSettings.mutate(undefined, { onSuccess: () => toast.success('Back to the defaults') })
            }
            disabled={!customised}
          >
            <Button icon={<UndoOutlined />} loading={resetSettings.isPending} disabled={!customised}>
              Reset
            </Button>
          </Popconfirm>
          <Button type="primary" icon={<SaveOutlined />} loading={updateSettings.isPending} onClick={() => form.submit()}>
            Save
          </Button>
        </Space>
      }
    >
      {status.data?.reachable === false && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`The model is not answering at ${status.data?.modelUrl ?? 'the configured address'}.`}
          description="Fetching still works; summarising waits for the model. Start it in chartering-ml: make serve-docker."
        />
      )}
      <Form<FormValues>
        form={form}
        layout="vertical"
        onFinish={(v) => updateSettings.mutate(v, { onSuccess: () => toast.success('Saved') })}
      >
        <Row gutter={16}>
          <Col xs={24} md={8}>
            <Form.Item
              label="Context window"
              required
              extra={hint(
                <>
                  Prompt, material and answer share it. Default {settings?.defaultContextWindowTokens.toLocaleString()} —
                  what the model is served with today.
                </>,
              )}
            >
              <Space.Compact style={{ width: '100%' }}>
                <Form.Item name="contextWindowTokens" noStyle rules={[{ required: true }]}>
                  <InputNumber min={2048} max={1048576} step={1024} addonAfter="tokens" style={{ width: '100%' }} />
                </Form.Item>
                <Button icon={<AimOutlined />} loading={detecting} onClick={detect}>
                  Detect
                </Button>
              </Space.Compact>
            </Form.Item>
          </Col>
          <Col xs={12} md={8}>
            <Form.Item
              name="summaryMaxTokens"
              label="Summary answer"
              rules={[{ required: true }]}
              extra={hint('Reserved for each topic summary.')}
            >
              <InputNumber min={128} max={32768} step={128} addonAfter="tokens" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={8}>
            <Form.Item
              name="notesMaxTokens"
              label="Notes answer"
              rules={[{ required: true }]}
              extra={hint('Reserved for each batch when the material does not fit one request.')}
            >
              <InputNumber min={64} max={16384} step={64} addonAfter="tokens" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={8}>
            <Form.Item
              name="lookbackDays"
              label="Summarise the last"
              rules={[{ required: true }]}
              extra={hint('Older items are not read.')}
            >
              <InputNumber min={1} max={365} addonAfter="days" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={12} md={8}>
            <Form.Item
              name="maxCallsPerTopic"
              label="Model calls per topic"
              rules={[{ required: true }]}
              extra={hint('Past it the lowest-ranked items are dropped, and the summary says how many.')}
            >
              <InputNumber min={1} max={200} style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item
              name="fetchIntervalMinutes"
              label="Fetch sources every"
              rules={[{ required: true }]}
              extra={hint(
                <>
                  <b>0 turns the timer off</b> — Fetch now on the Feed tab still works.
                </>,
              )}
            >
              <InputNumber min={0} max={1440} step={15} addonAfter="min" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>

        <Divider style={{ marginTop: 0 }} orientation="left" plain>
          <Space size={4} wrap>
            Which model writes the summaries
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
              extra={hint(
                <>
                  Its own server on purpose: the parser's model is an extraction finetune, and on
                  real feed items it missed openings that were there and wrote rates that were
                  not. <b>Clear it</b> to go back to <code>{settings?.defaultModelUrl}</code>.
                  Pointing it at the parser's address is supported — a summary run then waits for
                  a sweep to finish, because the two share one KV cache.
                </>,
              )}
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
              extra={hint('Sent only when set. llama-server ignores it; an Ollama needs it.')}
            >
              <Input placeholder="none" allowClear autoComplete="off" spellCheck={false} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Card>
  );
}
