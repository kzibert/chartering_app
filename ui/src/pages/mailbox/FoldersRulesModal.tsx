import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Empty,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Radio,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  FolderAddOutlined,
  PlusOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  useMailFolderMutations,
  useMailFolders,
  useMailRuleMutations,
  useMailRules,
} from '../../mailbox/store';
import type {
  MailFolder,
  MailRule,
  MailRuleCondition,
  MailRuleField,
  MailRuleOperator,
} from '../../api/types';

interface Props {
  open: boolean;
  onClose: () => void;
}

const FIELDS: { value: MailRuleField; label: string; hint?: string }[] = [
  { value: 'FROM', label: 'Sender', hint: 'The address and the display name together' },
  {
    value: 'FROM_DOMAIN',
    label: 'Sender domain',
    hint: 'Only the part after the @, so a display name quoting the domain cannot match',
  },
  { value: 'TO', label: 'Recipients', hint: 'To and Cc' },
  { value: 'SUBJECT', label: 'Subject' },
  { value: 'BODY', label: 'Message text' },
  { value: 'ANY', label: 'Anywhere', hint: 'Sender, recipients, subject and text' },
];

const OPERATORS: { value: MailRuleOperator; label: string }[] = [
  { value: 'CONTAINS', label: 'contains' },
  { value: 'NOT_CONTAINS', label: 'does not contain' },
  { value: 'EQUALS', label: 'is exactly' },
  { value: 'STARTS_WITH', label: 'starts with' },
  { value: 'ENDS_WITH', label: 'ends with' },
];

const labelOf = <T extends string>(list: { value: T; label: string }[], value: T) =>
  list.find((o) => o.value === value)?.label ?? value;

/**
 * Where folders and rules are managed.
 *
 * <p>The two live in one dialog because they are one idea: a folder without a rule is a
 * place to drag mail into, and a rule without a folder cannot be written at all. Splitting
 * them across two screens would mean leaving one to create the thing the other needs.
 */
export default function FoldersRulesModal({ open, onClose }: Props) {
  const [editing, setEditing] = useState<MailRule | null | undefined>(undefined);

  return (
    <>
      <Modal open={open} onCancel={onClose} footer={null} width={900} title="Folders & rules">
        <Tabs
          items={[
            { key: 'folders', label: 'Folders', children: <FoldersTab /> },
            {
              key: 'rules',
              label: 'Rules',
              children: <RulesTab onEdit={(r) => setEditing(r)} />,
            },
          ]}
        />
      </Modal>
      {/* `null` means "new rule", `undefined` means the form is closed — the same
          open/editing convention the other forms in this app use. */}
      <RuleForm
        open={editing !== undefined}
        editing={editing ?? null}
        onClose={() => setEditing(undefined)}
      />
    </>
  );
}

// ---------------------------------------------------------------- folders

function FoldersTab() {
  const { message } = App.useApp();
  const folders = useMailFolders();
  const { create, update, remove } = useMailFolderMutations();
  const [name, setName] = useState('');

  // The Inbox is in the rail but is not a row, so it is not editable here.
  const rows = (folders.data ?? []).filter((f) => f.id != null);

  const add = () => {
    if (!name.trim()) return;
    create.mutate(
      { name: name.trim() },
      {
        onSuccess: () => {
          message.success(`Folder "${name.trim()}" created`);
          setName('');
        },
      },
    );
  };

  const columns: ColumnsType<MailFolder> = [
    {
      title: 'Folder',
      dataIndex: 'name',
      render: (value: string, f) => (
        <Typography.Text
          editable={{
            tooltip: 'Rename',
            onChange: (v) =>
              v.trim() && v.trim() !== value &&
              update.mutate({ id: f.id!, body: { name: v.trim(), notes: f.notes, sortOrder: f.sortOrder } }),
          }}
        >
          {value}
        </Typography.Text>
      ),
    },
    {
      title: 'Order',
      dataIndex: 'sortOrder',
      width: 90,
      render: (value: number, f) => (
        <InputNumber
          size="small"
          value={value}
          style={{ width: 70 }}
          onChange={(v) =>
            update.mutate({ id: f.id!, body: { name: f.name, notes: f.notes, sortOrder: v ?? 0 } })
          }
        />
      ),
    },
    { title: 'Messages', dataIndex: 'total', width: 100 },
    {
      title: 'Unread',
      dataIndex: 'unread',
      width: 90,
      render: (v: number) => (v > 0 ? <Tag color="blue">{v}</Tag> : v),
    },
    {
      title: '',
      width: 60,
      render: (_, f) => (
        <Popconfirm
          title={`Delete "${f.name}"?`}
          description={
            f.total > 0
              ? `Its ${f.total} message${f.total === 1 ? '' : 's'} go back to the Inbox. Rules filing into it are deleted too.`
              : 'Rules filing into it are deleted too.'
          }
          okText="Delete"
          okButtonProps={{ danger: true }}
          onConfirm={() =>
            remove.mutate(f.id!, { onSuccess: () => message.success('Folder deleted') })
          }
        >
          <Button type="text" danger size="small" icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="These folders are the app's own"
        description="Filing a message here moves a row in this database — it never touches
                     your real mailbox, which the app only ever reads. Deleting a folder
                     returns its mail to the Inbox rather than deleting it."
      />
      <Space.Compact style={{ width: '100%' }}>
        <Input
          placeholder="New folder name…"
          value={name}
          onChange={(e) => setName(e.target.value)}
          onPressEnter={add}
        />
        <Button type="primary" icon={<FolderAddOutlined />} onClick={add} loading={create.isPending}>
          Add
        </Button>
      </Space.Compact>
      <Table<MailFolder>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={rows}
        pagination={false}
        loading={folders.isLoading}
        locale={{ emptyText: <Empty description="No folders yet" /> }}
      />
    </Space>
  );
}

// ---------------------------------------------------------------- rules

function RulesTab({ onEdit }: { onEdit: (rule: MailRule | null) => void }) {
  const { message, modal } = App.useApp();
  const rules = useMailRules();
  const { update, remove, apply } = useMailRuleMutations();

  const runNow = () =>
    modal.confirm({
      title: 'Apply the rules to the mail already synced?',
      content:
        'Mail in the Inbox, and mail a rule filed before, is re-filed by the rules as they ' +
        'stand now. Anything you filed by hand is left where you put it.',
      okText: 'Apply now',
      onOk: () =>
        apply.mutateAsync().then((r) =>
          message.success(
            `Evaluated ${r.evaluated} message${r.evaluated === 1 ? '' : 's'}: ` +
              `${r.filed} re-filed, ${r.markedRead} marked read`,
          ),
        ),
    });

  const columns: ColumnsType<MailRule> = [
    {
      title: 'On',
      dataIndex: 'enabled',
      width: 60,
      render: (enabled: boolean, r) => (
        <Switch
          size="small"
          checked={enabled}
          onChange={(v) => update.mutate({ id: r.id, body: { ...r, enabled: v } })}
        />
      ),
    },
    {
      title: 'Rule',
      dataIndex: 'name',
      render: (value: string, r) => (
        <div>
          <Typography.Text strong={r.enabled}>{value}</Typography.Text>
          <div>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {r.matchType === 'ALL' ? 'all of: ' : 'any of: '}
              {r.conditions
                .map(
                  (c) =>
                    `${labelOf(FIELDS, c.field)} ${labelOf(OPERATORS, c.operator)} "${c.value}"`,
                )
                .join(r.matchType === 'ALL' ? ' and ' : ' or ')}
            </Typography.Text>
          </div>
        </div>
      ),
    },
    {
      title: 'Files into',
      dataIndex: 'folderName',
      width: 160,
      render: (name: string, r) => (
        <Space size={4} wrap>
          <Tag color="geekblue">{name}</Tag>
          {r.markRead && <Tag>mark read</Tag>}
        </Space>
      ),
    },
    {
      title: (
        <Tooltip title="Rules run lowest first, and the first one that matches claims the message">
          Order
        </Tooltip>
      ),
      dataIndex: 'sortOrder',
      width: 90,
      render: (value: number, r) => (
        <InputNumber
          size="small"
          value={value}
          style={{ width: 70 }}
          onChange={(v) => update.mutate({ id: r.id, body: { ...r, sortOrder: v ?? 0 } })}
        />
      ),
    },
    {
      title: '',
      width: 90,
      render: (_, r) => (
        <Space size={0}>
          <Button type="text" size="small" icon={<EditOutlined />} onClick={() => onEdit(r)} />
          <Popconfirm
            title={`Delete "${r.name}"?`}
            description="Mail it already filed stays where it is."
            okText="Delete"
            okButtonProps={{ danger: true }}
            onConfirm={() =>
              remove.mutate(r.id, { onSuccess: () => message.success('Rule deleted') })
            }
          >
            <Button type="text" danger size="small" icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        message="Rules run as mail arrives, first match wins"
        description="A message lives in one folder, so the first rule that matches claims it
                     and the rest are not consulted. Editing a rule does not re-file anything
                     on its own — use Apply now for that."
      />
      <Space>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => onEdit(null)}>
          New rule
        </Button>
        <Button icon={<ThunderboltOutlined />} loading={apply.isPending} onClick={runNow}>
          Apply now to existing mail
        </Button>
      </Space>
      <Table<MailRule>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={rules.data ?? []}
        pagination={false}
        loading={rules.isLoading}
        locale={{ emptyText: <Empty description="No rules yet — new mail all stays in the Inbox" /> }}
      />
    </Space>
  );
}

// ---------------------------------------------------------------- the rule form

const BLANK: MailRuleCondition = { field: 'FROM_DOMAIN', operator: 'CONTAINS', value: '' };

function RuleForm({
  open,
  editing,
  onClose,
}: {
  open: boolean;
  editing: MailRule | null;
  onClose: () => void;
}) {
  const { message } = App.useApp();
  const folders = useMailFolders();
  const { create, update } = useMailRuleMutations();

  const [name, setName] = useState('');
  const [folderId, setFolderId] = useState<number>();
  const [matchType, setMatchType] = useState<'ALL' | 'ANY'>('ALL');
  const [markRead, setMarkRead] = useState(false);
  const [conditions, setConditions] = useState<MailRuleCondition[]>([{ ...BLANK }]);

  useEffect(() => {
    if (!open) return;
    setName(editing?.name ?? '');
    setFolderId(editing?.folderId);
    setMatchType(editing?.matchType ?? 'ALL');
    setMarkRead(editing?.markRead ?? false);
    setConditions(editing?.conditions.length ? editing.conditions.map((c) => ({ ...c })) : [{ ...BLANK }]);
  }, [open, editing]);

  const named = (folders.data ?? []).filter((f) => f.id != null);
  const filled = conditions.filter((c) => c.value.trim());
  const valid = name.trim() && folderId != null && filled.length > 0;

  const patch = (i: number, change: Partial<MailRuleCondition>) =>
    setConditions((cs) => cs.map((c, idx) => (idx === i ? { ...c, ...change } : c)));

  const submit = () => {
    const body = {
      name: name.trim(),
      folderId: folderId!,
      enabled: editing?.enabled ?? true,
      sortOrder: editing?.sortOrder,
      matchType,
      markRead,
      conditions: filled.map((c) => ({ field: c.field, operator: c.operator, value: c.value.trim() })),
    };
    const done = () => {
      message.success(editing ? 'Rule updated' : 'Rule created');
      onClose();
    };
    if (editing) update.mutate({ id: editing.id, body }, { onSuccess: done });
    else create.mutate(body, { onSuccess: done });
  };

  return (
    <Modal
      open={open}
      title={editing ? `Edit "${editing.name}"` : 'New rule'}
      okText={editing ? 'Save' : 'Create'}
      okButtonProps={{ disabled: !valid, loading: create.isPending || update.isPending }}
      onOk={submit}
      onCancel={onClose}
      width={720}
      destroyOnClose
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space wrap style={{ width: '100%' }}>
          <Input
            placeholder="Rule name, e.g. Maersk positions"
            value={name}
            onChange={(e) => setName(e.target.value)}
            style={{ width: 300 }}
          />
          <span>file into</span>
          <Select
            placeholder="Folder"
            value={folderId}
            onChange={setFolderId}
            style={{ width: 200 }}
            options={named.map((f) => ({ value: f.id!, label: f.name }))}
            notFoundContent="Create a folder first"
          />
        </Space>

        <Space wrap>
          <Radio.Group
            size="small"
            value={matchType}
            onChange={(e) => setMatchType(e.target.value)}
            optionType="button"
            options={[
              { value: 'ALL', label: 'Match all conditions' },
              { value: 'ANY', label: 'Match any condition' },
            ]}
          />
          <Tooltip title="For the mail you keep but never open">
            <Switch size="small" checked={markRead} onChange={setMarkRead} /> mark it read
          </Tooltip>
        </Space>

        {conditions.map((c, i) => (
          <Space key={i} wrap align="start">
            <Select
              value={c.field}
              style={{ width: 160 }}
              onChange={(v) => patch(i, { field: v })}
              options={FIELDS.map((f) => ({
                value: f.value,
                label: f.hint ? (
                  <Tooltip title={f.hint}>
                    <span>{f.label}</span>
                  </Tooltip>
                ) : (
                  f.label
                ),
              }))}
            />
            <Select
              value={c.operator}
              style={{ width: 160 }}
              onChange={(v) => patch(i, { operator: v })}
              options={OPERATORS}
            />
            <Input
              placeholder={c.field === 'FROM_DOMAIN' ? 'maersk.com' : 'text to look for'}
              value={c.value}
              style={{ width: 260 }}
              onChange={(e) => patch(i, { value: e.target.value })}
            />
            <Button
              type="text"
              danger
              icon={<DeleteOutlined />}
              disabled={conditions.length === 1}
              onClick={() => setConditions((cs) => cs.filter((_, idx) => idx !== i))}
            />
          </Space>
        ))}

        <Button
          size="small"
          icon={<PlusOutlined />}
          onClick={() => setConditions((cs) => [...cs, { ...BLANK }])}
        >
          Add condition
        </Button>

        {conditions.some((c) => c.field === 'BODY' || c.field === 'ANY') && (
          <Alert
            type="warning"
            showIcon
            message="Conditions on the message text are the slow ones"
            description="They read every stored body when the rules are re-run over existing
                         mail. On new mail arriving they cost nothing — the body is in hand
                         already."
          />
        )}
      </Space>
    </Modal>
  );
}
