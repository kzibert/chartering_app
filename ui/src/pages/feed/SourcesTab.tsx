import { useEffect, useState } from 'react';
import { App, Button, Form, Input, Modal, Popconfirm, Select, Space, Switch, Tag, Tooltip, Typography } from 'antd';
import { CloudDownloadOutlined, DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import ResponsiveTable from '../../components/ResponsiveTable';
import type { FeedSource, FeedSourceKind, FeedSourceRequest } from '../../api/feed';
import { useFeedMutations, useFeedParsers, useFeedSources } from '../../feed/store';

const KIND_LABEL: Record<FeedSourceKind, string> = { TELEGRAM: 'Telegram', RSS: 'RSS / Atom', WEBSITE: 'Website' };
const KIND_COLOR: Record<FeedSourceKind, string> = { TELEGRAM: 'cyan', RSS: 'orange', WEBSITE: 'geekblue' };

/**
 * Where the Feed reads from.
 *
 * <p>Telegram and RSS follow one rule each, so any public channel or any feed is just an address.
 * A website needs a parser written for it, chosen from the ones the app has. Removing a source
 * removes what it collected; switching it off keeps that and stops the fetching.
 */
export default function SourcesTab({ analysis }: { analysis: boolean }) {
  const { message: toast } = App.useApp();
  const sources = useFeedSources();
  const parsers = useFeedParsers();
  const { createSource, updateSource, deleteSource, fetchSource } = useFeedMutations();
  const [editing, setEditing] = useState<FeedSource | 'new'>();
  const parserLabel = (key?: string) => parsers.data?.find((p) => p.key === key)?.label ?? key;

  const setEnabled = (s: FeedSource, enabled: boolean) =>
    updateSource.mutate({
      id: s.id,
      body: {
        name: s.name,
        kind: s.kind,
        url: s.url,
        parserKey: s.parserKey,
        enabled,
        // Sent as it stands rather than left out: the update applies what it is given, and a
        // toggle of one flag must not quietly clear the other.
        intoIntake: s.intoIntake,
      },
    });

  const lastFetch = (s: FeedSource) =>
    !s.lastFetchedAt ? (
      <Typography.Text type="secondary">never</Typography.Text>
    ) : (
      <Space size={4} wrap>
        <Typography.Text style={{ fontSize: 13 }}>{dayjs(s.lastFetchedAt).format('D MMM HH:mm')}</Typography.Text>
        {s.lastError ? (
          <Tooltip title={s.lastError}>
            <Tag color="red">failed</Tag>
          </Tooltip>
        ) : (
          <Tag>{s.lastNewItems ?? 0} new</Tag>
        )}
      </Space>
    );

  const actions = (s: FeedSource) => (
    <Space size={4}>
      {analysis && (
        <Tooltip title="Fetch this source now">
          <Button
            size="small"
            icon={<CloudDownloadOutlined />}
            onClick={() => fetchSource.mutate(s.id, { onSuccess: () => toast.info(`Fetching ${s.name}…`) })}
          />
        </Tooltip>
      )}
      <Button size="small" icon={<EditOutlined />} onClick={() => setEditing(s)} aria-label="Edit" />
      <Popconfirm
        title={`Remove ${s.name}?`}
        description={`Its ${s.itemCount} collected item(s) go with it. Switch it off instead to keep them.`}
        onConfirm={() => deleteSource.mutate(s.id)}
      >
        <Button size="small" danger icon={<DeleteOutlined />} aria-label="Delete" />
      </Popconfirm>
    </Space>
  );

  const columns: ColumnsType<FeedSource> = [
    {
      title: 'On',
      key: 'enabled',
      width: 60,
      render: (_, s) => <Switch size="small" checked={s.enabled} onChange={(v) => setEnabled(s, v)} />,
    },
    {
      title: 'Source',
      key: 'name',
      render: (_, s) => (
        <Space direction="vertical" size={0}>
          <Space size={4}>
            <Tag color={KIND_COLOR[s.kind]}>{KIND_LABEL[s.kind]}</Tag>
            <Typography.Text strong>{s.name}</Typography.Text>
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            <a href={s.url} target="_blank" rel="noreferrer">
              {s.url}
            </a>
            {s.parserKey ? ` · ${parserLabel(s.parserKey)}` : ''}
          </Typography.Text>
          {s.intoIntake && (
            <Tooltip title="Its posts go through the email parser as circulars: cargoes onto the Cargoes tab, positions onto Open fleet, and a question in the Intake queue where the firm that signed is not on file.">
              <Tag color="cyan" style={{ marginTop: 2 }}>read into Intake</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    { title: 'Items', dataIndex: 'itemCount', key: 'items', width: 80 },
    { title: 'Last fetch', key: 'last', width: 180, render: (_, s) => lastFetch(s) },
    { title: '', key: 'actions', width: 120, render: (_, s) => actions(s) },
  ];

  return (
    <>
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setEditing('new')}>
          Add source
        </Button>
      </Space>
      <ResponsiveTable<FeedSource>
        rowKey={(s) => s.id}
        size="small"
        loading={sources.isLoading}
        columns={columns}
        dataSource={sources.data ?? []}
        pagination={false}
        mobile={{
          title: (s) => (
            <Space size={4}>
              <Tag color={KIND_COLOR[s.kind]}>{KIND_LABEL[s.kind]}</Tag>
              {s.name}
            </Space>
          ),
          subtitle: (s) => s.url,
          fields: (s) => [
            { label: 'On', value: <Switch size="small" checked={s.enabled} onChange={(v) => setEnabled(s, v)} /> },
            s.intoIntake && { label: 'Read into Intake', value: 'yes' },
            { label: 'Items', value: s.itemCount },
            { label: 'Last fetch', value: lastFetch(s) },
          ],
          actions,
        }}
      />
      <SourceModal
        source={editing}
        saving={createSource.isPending || updateSource.isPending}
        onClose={() => setEditing(undefined)}
        onSave={(body) => {
          const done = { onSuccess: () => { toast.success('Saved'); setEditing(undefined); } };
          if (editing === 'new') createSource.mutate(body, done);
          else if (editing) updateSource.mutate({ id: editing.id, body }, done);
        }}
      />
    </>
  );
}

function SourceModal({
  source,
  saving,
  onClose,
  onSave,
}: {
  source?: FeedSource | 'new';
  saving: boolean;
  onClose: () => void;
  onSave: (body: FeedSourceRequest) => void;
}) {
  const [form] = Form.useForm<FeedSourceRequest>();
  const parsers = useFeedParsers();
  const kind = Form.useWatch('kind', form);
  const parserKey = Form.useWatch('parserKey', form);

  useEffect(() => {
    if (source === 'new')
      form.setFieldsValue({
        kind: 'TELEGRAM',
        url: '',
        name: '',
        parserKey: undefined,
        enabled: true,
        intoIntake: false,
      });
    else if (source) form.setFieldsValue({ ...source });
  }, [source, form]);

  const placeholder =
    kind === 'TELEGRAM'
      ? '@channel or https://t.me/channel'
      : kind === 'RSS'
        ? 'https://example.com/feed/'
        : (parsers.data?.find((p) => p.key === parserKey)?.exampleUrl ?? 'https://…');

  return (
    <Modal
      open={source != null}
      title={source === 'new' ? 'Add source' : 'Edit source'}
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={saving}
      okText="Save"
      destroyOnClose
    >
      <Form form={form} layout="vertical" onFinish={onSave}>
        <Form.Item name="kind" label="Kind" rules={[{ required: true }]}>
          <Select
            options={[
              { value: 'TELEGRAM', label: 'Telegram channel (public, no account)' },
              { value: 'RSS', label: 'RSS or Atom feed' },
              { value: 'WEBSITE', label: 'Website with its own parser' },
            ]}
          />
        </Form.Item>
        {kind === 'WEBSITE' && (
          <Form.Item name="parserKey" label="Parser" rules={[{ required: true, message: 'Choose a parser' }]}>
            <Select options={(parsers.data ?? []).map((p) => ({ value: p.key, label: p.label }))} />
          </Form.Item>
        )}
        <Form.Item
          name="url"
          label={kind === 'TELEGRAM' ? 'Channel' : 'Address'}
          rules={[{ required: true, message: 'An address is required' }]}
          extra={
            kind === 'TELEGRAM'
              ? 'Read through t.me’s public web preview. Private channels and groups cannot be read.'
              : undefined
          }
        >
          <Input placeholder={placeholder} />
        </Form.Item>
        <Form.Item name="name" label="Name" extra="Leave blank to use the handle or the site's address.">
          <Input maxLength={200} />
        </Form.Item>
        <Form.Item name="enabled" label="Fetch on the timer" valuePropName="checked">
          <Switch />
        </Form.Item>
        <Form.Item
          name="intoIntake"
          label="Read into Intake"
          valuePropName="checked"
          extra={
            "Send its posts through the email parser as circulars — cargoes, open positions " +
            "and the firm that signed them. Worth it for a board of pasted circulars like " +
            "ship.gr's; not for a news feed, where the extraction model spends GPU to produce " +
            "nothing. Summarising is separate and unaffected."
          }
        >
          <Switch />
        </Form.Item>
      </Form>
    </Modal>
  );
}
