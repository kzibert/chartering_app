import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Checkbox,
  Col,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Switch,
  Tag,
  Typography,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, SaveOutlined, UndoOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import ResponsiveTable from '../../components/ResponsiveTable';
import type { FeedTopic, FeedTopicRequest } from '../../api/feed';
import { useFeedMutations, useFeedSettings, useFeedTopics } from '../../feed/store';

/**
 * What the desk wants summaries about, and the prompt they are written under.
 *
 * <p>Side by side on purpose: the prompt names {topic} and {keywords}, so the words chosen here
 * are the words the model is given. Both are rows in the database and edit the same from every
 * deployment.
 */
export default function TopicsTab() {
  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <TopicsCard />
      <PromptsCard />
    </Space>
  );
}

function TopicsCard() {
  const { message: toast } = App.useApp();
  const topics = useFeedTopics();
  const { createTopic, updateTopic, deleteTopic, selectTopics } = useFeedMutations();
  const [editing, setEditing] = useState<FeedTopic | 'new'>();
  const rows = topics.data ?? [];

  const toggle = (t: FeedTopic, on: boolean) => {
    const ids = rows.filter((r) => (r.id === t.id ? on : r.selected)).map((r) => r.id);
    selectTopics.mutate(ids);
  };

  const columns: ColumnsType<FeedTopic> = [
    {
      title: 'Summarise',
      key: 'selected',
      width: 100,
      render: (_, t) => <Checkbox checked={t.selected} onChange={(e) => toggle(t, e.target.checked)} />,
    },
    { title: 'Topic', dataIndex: 'name', key: 'name' },
    {
      title: 'Keywords',
      key: 'keywords',
      render: (_, t) =>
        t.keywords.length ? (
          <Space wrap size={4}>
            {t.keywords.map((k) => (
              <Tag key={k}>{k}</Tag>
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">the words of the name</Typography.Text>
        ),
    },
    {
      title: '',
      key: 'actions',
      width: 100,
      render: (_, t) => <TopicActions topic={t} onEdit={() => setEditing(t)} onDelete={() => deleteTopic.mutate(t.id)} />,
    },
  ];

  return (
    <Card
      title="Topics"
      extra={
        <Button icon={<PlusOutlined />} onClick={() => setEditing('new')}>
          Add topic
        </Button>
      }
    >
      <Typography.Paragraph type="secondary">
        Summarise writes one summary per ticked topic. An item is read for a topic only if it mentions one of
        its keywords, so they decide what reaches the model — "handy", "black sea", "danube", "$/t".
      </Typography.Paragraph>
      <ResponsiveTable<FeedTopic>
        rowKey={(t) => t.id}
        size="small"
        loading={topics.isLoading}
        columns={columns}
        dataSource={rows}
        pagination={false}
        mobile={{
          title: (t) => (
            <Space>
              <Checkbox checked={t.selected} onChange={(e) => toggle(t, e.target.checked)} />
              {t.name}
            </Space>
          ),
          fields: (t) => [{ label: 'Keywords', value: t.keywords.join(', ') || 'the words of the name' }],
          actions: (t) => (
            <TopicActions topic={t} onEdit={() => setEditing(t)} onDelete={() => deleteTopic.mutate(t.id)} />
          ),
        }}
      />
      <TopicModal
        topic={editing}
        saving={createTopic.isPending || updateTopic.isPending}
        onClose={() => setEditing(undefined)}
        onSave={(body) => {
          const done = { onSuccess: () => { toast.success('Saved'); setEditing(undefined); } };
          if (editing === 'new') createTopic.mutate(body, done);
          else if (editing) updateTopic.mutate({ id: editing.id, body }, done);
        }}
      />
    </Card>
  );
}

function TopicActions({ topic, onEdit, onDelete }: { topic: FeedTopic; onEdit: () => void; onDelete: () => void }) {
  return (
    <Space size={4}>
      <Button size="small" icon={<EditOutlined />} onClick={onEdit} aria-label="Edit" />
      <Popconfirm
        title={`Remove "${topic.name}"?`}
        description="Its summaries stay, under this name."
        onConfirm={onDelete}
      >
        <Button size="small" danger icon={<DeleteOutlined />} aria-label="Delete" />
      </Popconfirm>
    </Space>
  );
}

function TopicModal({
  topic,
  saving,
  onClose,
  onSave,
}: {
  topic?: FeedTopic | 'new';
  saving: boolean;
  onClose: () => void;
  onSave: (body: FeedTopicRequest) => void;
}) {
  const [form] = Form.useForm<FeedTopicRequest>();
  useEffect(() => {
    if (topic === 'new') form.setFieldsValue({ name: '', keywords: [], selected: true });
    else if (topic) form.setFieldsValue({ name: topic.name, keywords: topic.keywords, selected: topic.selected });
  }, [topic, form]);

  return (
    <Modal
      open={topic != null}
      title={topic === 'new' ? 'Add topic' : 'Edit topic'}
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={saving}
      okText="Save"
      destroyOnClose
    >
      <Form form={form} layout="vertical" onFinish={onSave}>
        <Form.Item name="name" label="Topic" rules={[{ required: true, message: 'A topic needs a name' }]}>
          <Input placeholder="e.g. Handysize freight, Med / Black Sea" maxLength={200} />
        </Form.Item>
        <Form.Item
          name="keywords"
          label="Keywords"
          extra="Type and press Enter or comma. Matched anywhere in an item, case ignored. Empty uses the words of the name."
        >
          <Select mode="tags" open={false} suffixIcon={null} tokenSeparators={[',']} placeholder="handy, black sea, danube" />
        </Form.Item>
        <Form.Item name="selected" label="Include in Summarise" valuePropName="checked">
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}

interface PromptValues {
  systemPrompt: string;
  notesPrompt: string;
}

function PromptsCard() {
  const { message: toast } = App.useApp();
  const [form] = Form.useForm<PromptValues>();
  const settings = useFeedSettings();
  const { updateSettings, resetPrompts } = useFeedMutations();
  const s = settings.data;

  useEffect(() => {
    if (s) form.setFieldsValue({ systemPrompt: s.systemPrompt, notesPrompt: s.notesPrompt });
  }, [s, form]);

  const customised = s?.systemPromptCustomised || s?.notesPromptCustomised;
  const mono = { fontFamily: 'monospace', fontSize: 12 };

  return (
    <Card
      title={
        <Space wrap>
          Prompts
          {customised ? <Tag color="blue">edited</Tag> : <Tag>defaults</Tag>}
        </Space>
      }
      loading={settings.isLoading}
      extra={
        <Space wrap>
          <Popconfirm
            title="Both prompts back to their defaults?"
            onConfirm={() => resetPrompts.mutate(undefined, { onSuccess: () => toast.success('Defaults restored') })}
            disabled={!customised}
          >
            <Button icon={<UndoOutlined />} disabled={!customised} loading={resetPrompts.isPending}>
              Reset
            </Button>
          </Popconfirm>
          <Button type="primary" icon={<SaveOutlined />} loading={updateSettings.isPending} onClick={() => form.submit()}>
            Save
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        Filled in per topic before sending:{' '}
        {(s?.placeholders ?? []).map((p) => (
          <Tag key={p} style={mono}>
            {p}
          </Tag>
        ))}
        Every summary keeps the prompt it was written under, so editing here does not change what old summaries say.
      </Typography.Paragraph>
      <Form form={form} layout="vertical" onFinish={(v) => updateSettings.mutate(v, { onSuccess: () => toast.success('Saved') })}>
        <Row gutter={16}>
          <Col xs={24} lg={12}>
            <Form.Item
              name="systemPrompt"
              label="Summary prompt"
              extra="Writes each topic's summary — from the items when they fit one request, from the notes when they do not."
            >
              <Input.TextArea autoSize={{ minRows: 12, maxRows: 30 }} style={mono} />
            </Form.Item>
          </Col>
          <Col xs={24} lg={12}>
            <Form.Item
              name="notesPrompt"
              label="Notes prompt"
              extra="Used only when the material is too large for one request: each batch becomes notes first."
            >
              <Input.TextArea autoSize={{ minRows: 12, maxRows: 30 }} style={mono} />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Card>
  );
}
