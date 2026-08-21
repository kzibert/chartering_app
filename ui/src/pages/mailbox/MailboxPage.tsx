import { useMemo, useState } from 'react';
import {
  Alert,
  App,
  Badge,
  Button,
  Card,
  Checkbox,
  Dropdown,
  Empty,
  Input,
  Menu,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  BankOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  InboxOutlined,
  MailOutlined,
  PaperClipOutlined,
  ReloadOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import {
  useMailFolders,
  useMailMessageMutations,
  useMailMessages,
  useMailboxStatus,
  useMailboxSync,
} from '../../mailbox/store';
import { usePersistedState } from '../../components/usePersistedState';
import { useTableControls } from '../../components/useTableControls';
import CompanyDrawer from '../companies/CompanyDrawer';
import MessageDrawer from './MessageDrawer';
import FoldersRulesModal from './FoldersRulesModal';
import type { MailMessage, MailboxFilter } from '../../api/types';

/**
 * Which folder the rail is showing. 'all' spans every folder, 'inbox' is the mail nothing
 * has filed, and a number is one folder — the three cases the API takes as (nothing),
 * unfiled=true and folderId.
 */
type Scope = 'all' | 'inbox' | number;

interface Filters {
  search: string;
  /** Scan the message bodies too. Off by default, and the reason is on the checkbox. */
  searchBody: boolean;
  unreadOnly: boolean;
  scope: Scope;
}

const DEFAULTS: Filters = { search: '', searchBody: false, unreadOnly: false, scope: 'inbox' };

/**
 * One line, truncated. Mail rows carry addresses and quoted subjects with no spaces in
 * them, which wrap into a column one character wide unless they are clamped outright.
 */
const CLAMP: React.CSSProperties = {
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  display: 'block',
  minWidth: 0,
};

/**
 * The Mailbox: incoming mail, linked to the companies it came from, filed into folders by
 * rules the desk writes.
 *
 * <p>The search is one field on purpose. An address, a person, a company name and a word
 * from the subject all go in the same box, because at the moment of looking for a message
 * nobody knows which of those they remember — and a row of four labelled inputs makes you
 * decide before you can type. The one thing kept out of it is the message text, which is
 * the checkbox beside the box: that search is an unindexed scan of every body in the table,
 * and it should cost what it costs only when it was asked for.
 */
export default function MailboxPage() {
  const { message } = App.useApp();
  const [filters, setFilters] = usePersistedState<Filters>('mailbox', DEFAULTS);
  // Typed but not yet searched. Kept apart from filters.search so that every keystroke is
  // not a query against the whole mail table — the search runs on Enter or the button.
  const [typed, setTyped] = useState(filters.search);
  const [openId, setOpenId] = useState<number>();
  const [companyId, setCompanyId] = useState<number>();
  const [manageOpen, setManageOpen] = useState(false);
  const [picked, setPicked] = useState<number[]>([]);

  const table = useTableControls({ size: 25, sort: 'receivedAt,desc' }, 'mailbox');
  const folders = useMailFolders();
  const status = useMailboxStatus();
  const sync = useMailboxSync();
  const { setReadBulk, moveBulk } = useMailMessageMutations();

  const query: MailboxFilter = useMemo(
    () => ({
      search: filters.search || undefined,
      searchBody: filters.searchBody || undefined,
      unfiled: filters.scope === 'inbox' ? true : undefined,
      folderId: typeof filters.scope === 'number' ? filters.scope : undefined,
      read: filters.unreadOnly ? false : undefined,
      page: table.state.page,
      size: table.state.size,
      sort: table.state.sort ?? 'receivedAt,desc',
    }),
    [filters, table.state],
  );
  const messages = useMailMessages(query);
  const rows = messages.data?.content ?? [];

  const update = (patch: Partial<Filters>) => {
    setFilters((f) => ({ ...f, ...patch }));
    setPicked([]);
    table.resetPage();
  };

  const runSearch = (value: string) => update({ search: value.trim() });

  const folderList = folders.data ?? [];
  const named = folderList.filter((f) => f.id != null);
  const inbox = folderList.find((f) => f.id == null);

  // ---- the folder rail ----------------------------------------------------------------
  const railItems = [
    {
      key: 'all',
      icon: <MailOutlined />,
      label: <RailLabel name="All mail" unread={status.data?.unread ?? 0} />,
    },
    {
      key: 'inbox',
      icon: <InboxOutlined />,
      label: <RailLabel name="Inbox" unread={inbox?.unread ?? 0} />,
    },
    ...named.map((f) => ({
      key: String(f.id),
      icon: <FolderOutlined />,
      label: <RailLabel name={f.name} unread={f.unread} />,
    })),
  ];

  const scopeKey = filters.scope === 'all' || filters.scope === 'inbox'
    ? filters.scope
    : String(filters.scope);

  // ---- bulk actions -------------------------------------------------------------------
  const moveMenu = {
    items: [
      { key: 'inbox', icon: <InboxOutlined />, label: 'Back to Inbox' },
      ...(named.length ? [{ type: 'divider' as const }] : []),
      ...named.map((f) => ({ key: String(f.id), icon: <FolderOutlined />, label: f.name })),
    ],
    onClick: ({ key }: { key: string }) => {
      const folderId = key === 'inbox' ? undefined : Number(key);
      moveBulk.mutate(
        { ids: picked, folderId },
        {
          onSuccess: (moved) => {
            const where = key === 'inbox' ? 'the Inbox' : named.find((f) => f.id === Number(key))?.name;
            message.success(`Moved ${moved} message${moved === 1 ? '' : 's'} to ${where}`);
            setPicked([]);
          },
        },
      );
    },
  };

  const markPicked = (read: boolean) =>
    setReadBulk.mutate(
      { ids: picked, read },
      {
        onSuccess: () => {
          message.success(`${picked.length} marked ${read ? 'read' : 'unread'}`);
          setPicked([]);
        },
      },
    );

  const columns: ColumnsType<MailMessage> = [
    {
      title: 'From',
      dataIndex: 'fromAddress',
      width: 240,
      render: (_: string, m) => (
        <div style={CLAMP}>
          <div style={CLAMP}>
            <Typography.Text strong={!m.read}>{m.fromName || m.fromAddress}</Typography.Text>
          </div>
          {m.fromName && (
            <div style={CLAMP}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {m.fromAddress}
              </Typography.Text>
            </div>
          )}
        </div>
      ),
    },
    {
      title: 'Subject',
      dataIndex: 'subject',
      render: (_: string, m) => (
        <div style={{ minWidth: 0 }}>
          {/* A flex row rather than a Space: the dot and the clip are fixed, the subject is
              the part that has to give way, and it only gives way if it is the flex item
              itself that may shrink to nothing. Wrapped in anything, a subject with no
              spaces in it keeps its full width and runs out over the Company column. */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
            {!m.read && <Badge status="processing" />}
            <Typography.Text
              strong={!m.read}
              ellipsis={{ tooltip: m.subject || undefined }}
              style={{ flex: 1, minWidth: 0 }}
            >
              {m.subject || '(no subject)'}
            </Typography.Text>
            {m.hasAttachments && (
              <Tooltip title="Has attachments — the app records their names, not the files">
                <PaperClipOutlined style={{ color: '#8c8c8c', flex: 'none' }} />
              </Tooltip>
            )}
          </div>
          {m.snippet && (
            <div style={{ ...CLAMP, fontSize: 12, color: '#8c8c8c' }}>{m.snippet}</div>
          )}
        </div>
      ),
    },
    {
      title: 'Company',
      dataIndex: 'companyName',
      width: 200,
      render: (_: string, m) =>
        m.companyId ? (
          // A link rather than a link-shaped Button, for the same reason the subject is not
          // a Space: a Button will not let its label be cut, so a shipowner with a long
          // registered name lays itself across the Folder and Received columns.
          <div style={{ display: 'flex', alignItems: 'center', gap: 4, minWidth: 0 }}>
            {/* Straight through to the company: the whole point of syncing the mail here
                rather than reading it in a mail client. */}
            <BankOutlined style={{ color: '#1677ff', flex: 'none' }} />
            <Tooltip title={m.companyName}>
              <Typography.Link
                ellipsis
                style={{ flex: 1, minWidth: 0 }}
                onClick={(e) => {
                  e.stopPropagation();
                  setCompanyId(m.companyId);
                }}
              >
                {m.companyName}
              </Typography.Link>
            </Tooltip>
            {m.linkManual && (
              <Tooltip title="Linked by hand — automatic re-linking will not change it">
                <Tag color="blue" style={{ flex: 'none', marginInlineEnd: 0 }}>
                  manual
                </Tag>
              </Tooltip>
            )}
          </div>
        ) : (
          <Typography.Text type="secondary">unknown sender</Typography.Text>
        ),
    },
    {
      title: 'Folder',
      dataIndex: 'folderName',
      width: 130,
      // Only meaningful when looking across folders; inside one it would be the same word
      // on every row.
      hidden: filters.scope !== 'all',
      render: (name: string | undefined, m) =>
        name ? (
          <Tag icon={<FolderOpenOutlined />} color={m.filedByRuleId ? 'geekblue' : 'default'}>
            {name}
          </Tag>
        ) : (
          <Tag icon={<InboxOutlined />}>Inbox</Tag>
        ),
    },
    {
      title: 'Received',
      dataIndex: 'receivedAt',
      width: 150,
      sorter: true,
      sortOrder: table.sortOrderFor('receivedAt'),
      render: (value: string) => <RelativeDate value={value} />,
    },
  ];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <SyncBanner
        status={status.data}
        onSync={() => sync.mutate(undefined, { onSuccess: () => message.info('Fetching mail…') })}
        syncing={status.data?.syncing || sync.isPending}
      />

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        <Card
          size="small"
          style={{ width: 230, flex: '0 0 230px' }}
          styles={{ body: { padding: 0 } }}
        >
          <Menu
            mode="inline"
            selectedKeys={[scopeKey]}
            items={railItems}
            style={{ borderInlineEnd: 0 }}
            onClick={({ key }) =>
              update({ scope: key === 'all' || key === 'inbox' ? key : Number(key) })
            }
          />
          <div style={{ padding: 8, borderTop: '1px solid rgba(5,5,5,0.06)' }}>
            <Button
              block
              size="small"
              icon={<SettingOutlined />}
              onClick={() => setManageOpen(true)}
            >
              Folders &amp; rules
            </Button>
          </div>
        </Card>

        <Card size="small" style={{ flex: 1, minWidth: 0 }}>
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            <Space wrap>
              <Input.Search
                allowClear
                value={typed}
                onChange={(e) => setTyped(e.target.value)}
                onSearch={runSearch}
                placeholder="Address, person, company, or words from the subject…"
                style={{ width: 420 }}
                enterButton
              />
              <Tooltip
                title="Also search inside the message text. Not indexed — every stored body is
                       scanned — so it is left off unless you ask for it."
              >
                <Checkbox
                  checked={filters.searchBody}
                  onChange={(e) => update({ searchBody: e.target.checked })}
                >
                  Search message text
                </Checkbox>
              </Tooltip>
              <Checkbox
                checked={filters.unreadOnly}
                onChange={(e) => update({ unreadOnly: e.target.checked })}
              >
                Unread only
              </Checkbox>
              {(filters.search || filters.searchBody || filters.unreadOnly) && (
                <Button
                  size="small"
                  onClick={() => {
                    setTyped('');
                    update({ search: '', searchBody: false, unreadOnly: false });
                  }}
                >
                  Reset
                </Button>
              )}
            </Space>

            {picked.length > 0 && (
              <Space wrap>
                <Typography.Text type="secondary">{picked.length} selected</Typography.Text>
                <Button size="small" onClick={() => markPicked(true)}>
                  Mark read
                </Button>
                <Button size="small" onClick={() => markPicked(false)}>
                  Mark unread
                </Button>
                <Dropdown menu={moveMenu} disabled={named.length === 0}>
                  <Button size="small" icon={<FolderOutlined />}>
                    Move to…
                  </Button>
                </Dropdown>
                <Button size="small" type="text" onClick={() => setPicked([])}>
                  Clear
                </Button>
              </Space>
            )}

            <Table<MailMessage>
              rowKey="id"
              size="small"
              // Without this the browser sizes columns by content, and one message with a
              // 300-character snippet squeezes the sender column down to a single character
              // per line while the rest of the table scrolls off to the right. Fixed layout
              // makes the widths above mean what they say; Subject takes the slack.
              tableLayout="fixed"
              loading={messages.isLoading}
              columns={columns}
              dataSource={rows}
              pagination={table.pagination(messages.data?.totalElements ?? 0)}
              onChange={table.onChange}
              rowSelection={{
                selectedRowKeys: picked,
                onChange: (keys) => setPicked(keys as number[]),
              }}
              onRow={(m) => ({ onClick: () => setOpenId(m.id), style: { cursor: 'pointer' } })}
              locale={{
                emptyText: (
                  <Empty
                    description={
                      filters.search
                        ? 'Nothing matches that search'
                        : status.data?.configured
                          ? 'No mail here yet'
                          : 'The mailbox is not configured — see the banner above'
                    }
                  />
                ),
              }}
            />
          </Space>
        </Card>
      </div>

      <MessageDrawer
        messageId={openId}
        onClose={() => setOpenId(undefined)}
        onOpenCompany={(id) => setCompanyId(id)}
      />
      {/* Opened read-only from a message: onEdit is a no-op here deliberately. Editing a
          company belongs on the Companies tab, and the drawer's own form would open behind
          the reading pane with no way back to the message that led here. */}
      <CompanyDrawer
        companyId={companyId}
        initialTab="contacts"
        onClose={() => setCompanyId(undefined)}
        onEdit={() => undefined}
      />
      <FoldersRulesModal open={manageOpen} onClose={() => setManageOpen(false)} />
    </Space>
  );
}

/** A folder name with its unread count — the badge is the only thing that draws the eye. */
function RailLabel({ name, unread }: { name: string; unread: number }) {
  return (
    <span style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
      <span>{name}</span>
      {unread > 0 && <Badge count={unread} size="small" overflowCount={999} />}
    </span>
  );
}

/** Today shows a time, this year a date, anything older the year too. */
function RelativeDate({ value }: { value: string }) {
  const d = dayjs(value);
  const format = d.isSame(dayjs(), 'day')
    ? 'HH:mm'
    : d.isSame(dayjs(), 'year')
      ? 'D MMM HH:mm'
      : 'D MMM YYYY';
  return (
    <Tooltip title={d.format('YYYY-MM-DD HH:mm')}>
      <span>{d.format(format)}</span>
    </Tooltip>
  );
}

/**
 * What the sync is doing, and why it is not doing it.
 *
 * <p>Shown above the mail rather than tucked into a settings screen: an inbox that stopped
 * syncing three days ago looks exactly like an inbox where nothing has arrived, and the
 * whole tab is misleading until that is said out loud.
 */
function SyncBanner({
  status,
  onSync,
  syncing,
}: {
  status?: import('../../api/types').MailboxStatus;
  onSync: () => void;
  syncing: boolean;
}) {
  if (!status) return null;

  const syncButton = (
    <Button
      size="small"
      icon={syncing ? <ThunderboltOutlined /> : <ReloadOutlined />}
      loading={syncing}
      disabled={!status.configured}
      onClick={onSync}
    >
      {syncing ? 'Fetching…' : 'Fetch now'}
    </Button>
  );

  if (!status.configured) {
    return (
      <Alert
        type="warning"
        showIcon
        message="The mailbox is not being read"
        description={
          <>
            Set {status.missingSettings.join(', ')} in <code>.env</code> and restart the api
            container. Until then this tab shows whatever was synced before.
          </>
        }
      />
    );
  }

  if (status.lastStatus === 'FAILED') {
    return (
      <Alert
        type="error"
        showIcon
        message={`Last sync failed — ${dayjs(status.lastSyncAt).format('YYYY-MM-DD HH:mm')}`}
        description={status.lastError}
        action={syncButton}
      />
    );
  }

  return (
    <Alert
      type="success"
      showIcon
      message={
        <Space wrap size={4}>
          <Tag color="green">{status.username}</Tag>
          <Tag>{status.folder}</Tag>
          <span>
            {status.lastSyncAt
              ? `last fetched ${dayjs(status.lastSyncAt).format('YYYY-MM-DD HH:mm')} — ` +
                `${status.lastStored} new of ${status.lastFetched} read`
              : 'not fetched yet'}
          </span>
          <Typography.Text type="secondary">
            · every {Math.round(status.pollIntervalMs / 60000)} min · {status.unread} unread of{' '}
            {status.totalMessages}
          </Typography.Text>
        </Space>
      }
      action={syncButton}
    />
  );
}
