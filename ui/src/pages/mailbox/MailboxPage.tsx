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
  Skeleton,
  Space,
  Table,
  Tree,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  BankOutlined,
  DeleteOutlined,
  EditOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  InboxOutlined,
  MailOutlined,
  PaperClipOutlined,
  ReloadOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { DataNode } from 'antd/es/tree';
import dayjs from 'dayjs';
import {
  useMailFolders,
  useMailMessageMutations,
  useMailMessages,
  useMailServerFolders,
  useMailboxStatus,
  useMailboxSync,
} from '../../mailbox/store';
import { usePersistedState } from '../../components/usePersistedState';
import { useTableControls } from '../../components/useTableControls';
import CompanyDrawer from '../companies/CompanyDrawer';
import MessageDrawer from './MessageDrawer';
import FoldersRulesModal from './FoldersRulesModal';
import type { MailMessage, MailServerFolder, MailboxFilter } from '../../api/types';

/**
 * Which folder the rail is showing.
 *
 * <p>Four cases, because there are two kinds of folder and they are different axes. A
 * {@code server} scope is a folder in the mailbox itself — where Zoho's own filters put the
 * message on arrival. A number is one of the app's folders, and 'inbox' is the mail no app
 * rule has claimed; neither of those says anything about where the mail server keeps it.
 * 'all' spans the lot. The API takes them as imapFolder, folderId, unfiled=true and nothing.
 *
 * <p>The string cases are kept as bare strings rather than tidied into one shape because
 * they are what is already in localStorage from before the server folders existed.
 */
type Scope = 'all' | 'inbox' | number | { server: string };

interface Filters {
  search: string;
  /** Scan the message bodies too. Off by default, and the reason is on the checkbox. */
  searchBody: boolean;
  unreadOnly: boolean;
  scope: Scope;
}

/** Opens on the mail server's Inbox — the same thing the mail client beside it opens on. */
const DEFAULTS: Filters = {
  search: '',
  searchBody: false,
  unreadOnly: false,
  scope: { server: 'INBOX' },
};

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
  const serverFolders = useMailServerFolders();
  const status = useMailboxStatus();
  const sync = useMailboxSync();
  const { setReadBulk, moveBulk } = useMailMessageMutations();

  const query: MailboxFilter = useMemo(
    () => ({
      search: filters.search || undefined,
      searchBody: filters.searchBody || undefined,
      unfiled: filters.scope === 'inbox' ? true : undefined,
      folderId: typeof filters.scope === 'number' ? filters.scope : undefined,
      imapFolder: serverScope(filters.scope),
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
  // Two sections, because there are two kinds of folder. The mailbox's own tree comes first:
  // it is where a message actually is, and on a mailbox with server-side filters it is the
  // only rail that accounts for every message. The app's folders sit below it as what this
  // desk has filed on top.
  /** Full name to the leaf the rail shows, so a row says "Handy" rather than "Brokers/Handy". */
  const serverNames = useMemo(
    () => new Map((serverFolders.data ?? []).map((f) => [f.fullName, f.displayName])),
    [serverFolders.data],
  );

  const serverTree = useMemo(
    () => toTree(serverFolders.data ?? []),
    [serverFolders.data],
  );

  const appItems = [
    {
      key: 'all',
      icon: <MailOutlined />,
      label: <RailLabel name="All mail" unread={status.data?.unread ?? 0} />,
    },
    {
      key: 'inbox',
      // Not "Inbox": there is a real one in the tree above now, and two rows meaning
      // different things under the same name is worse than a plainer word for this one.
      icon: <InboxOutlined />,
      label: <RailLabel name="Unfiled" unread={inbox?.unread ?? 0} />,
    },
    ...named.map((f) => ({
      key: String(f.id),
      icon: <FolderOutlined />,
      label: <RailLabel name={f.name} unread={f.unread} />,
    })),
  ];

  const scopeKey = typeof filters.scope === 'object' ? null : String(filters.scope);

  // ---- bulk actions -------------------------------------------------------------------
  const moveMenu = {
    items: [
      { key: 'inbox', icon: <InboxOutlined />, label: 'Take out of the folder' },
      ...(named.length ? [{ type: 'divider' as const }] : []),
      ...named.map((f) => ({ key: String(f.id), icon: <FolderOutlined />, label: f.name })),
    ],
    onClick: ({ key }: { key: string }) => {
      const folderId = key === 'inbox' ? undefined : Number(key);
      moveBulk.mutate(
        { ids: picked, folderId },
        {
          onSuccess: (moved) => {
            const where = named.find((f) => f.id === Number(key))?.name;
            message.success(
              key === 'inbox'
                ? `Took ${moved} message${moved === 1 ? '' : 's'} out of their folder`
                : `Moved ${moved} message${moved === 1 ? '' : 's'} to ${where}`,
            );
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
      dataIndex: 'imapFolder',
      width: 190,
      // Both folders a message can be in, and they are different statements: the first is
      // where the mail server keeps it, the second what this app has filed on top. The
      // mailbox one is dropped while browsing that very folder, where it would be the same
      // word on every row.
      render: (_: string, m) => (
        <Space size={4} wrap>
          {m.imapFolder && serverScope(filters.scope) !== m.imapFolder && (
            <Tooltip title={`In the mailbox: ${m.imapFolder}`}>
              <Tag>{serverNames.get(m.imapFolder) ?? m.imapFolder}</Tag>
            </Tooltip>
          )}
          {m.folderName && (
            <Tooltip
              title={
                m.filedByRuleId
                  ? "Filed here by one of this app's rules"
                  : 'Filed here by hand, in this app'
              }
            >
              <Tag icon={<FolderOpenOutlined />} color={m.filedByRuleId ? 'geekblue' : 'default'}>
                {m.folderName}
              </Tag>
            </Tooltip>
          )}
        </Space>
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
          style={{ width: 250, flex: '0 0 250px' }}
          styles={{ body: { padding: 0 } }}
        >
          <Menu
            mode="inline"
            selectedKeys={scopeKey ? [scopeKey] : []}
            items={appItems.slice(0, 1)}
            style={{ borderInlineEnd: 0 }}
            onClick={() => update({ scope: 'all' })}
          />

          <RailSection
            title="In the mailbox"
            hint="The folders as they are on the mail server, refreshed on every sync. This is
                  where the mailbox's own filters put each message as it arrived — the app
                  mirrors that and never moves anything on the server."
          />
          {serverFolders.isLoading ? (
            <div style={{ padding: 12 }}>
              <Skeleton active paragraph={{ rows: 4 }} title={false} />
            </div>
          ) : serverTree.length === 0 ? (
            <Typography.Text type="secondary" style={{ display: 'block', padding: '4px 12px 10px' }}>
              Not listed yet — they appear after the first sync.
            </Typography.Text>
          ) : (
            <Tree
              treeData={serverTree}
              blockNode
              defaultExpandAll
              selectedKeys={typeof filters.scope === 'object' ? [filters.scope.server] : []}
              onSelect={(keys) => {
                // Clicking the selected row again would otherwise clear the scope and show
                // nothing, which is not a state the rail can express.
                if (keys.length) update({ scope: { server: String(keys[0]) } });
              }}
              style={{ padding: '0 8px 8px' }}
            />
          )}

          <RailSection
            title="Filed by this app"
            hint="The app's own folders and the rules that fill them. They file a copy of the
                  filing, so to speak: a message keeps sitting in the mailbox folder it
                  arrived in, and nothing here is ever written back to the server."
          />
          <Menu
            mode="inline"
            selectedKeys={scopeKey && scopeKey !== 'all' ? [scopeKey] : []}
            items={appItems.slice(1)}
            style={{ borderInlineEnd: 0 }}
            onClick={({ key }) => update({ scope: key === 'inbox' ? key : Number(key) })}
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

/** The scope as the API's imapFolder parameter, and nothing at all when it is not one. */
function serverScope(scope: Scope): string | undefined {
  return typeof scope === 'object' ? scope.server : undefined;
}

/** A heading over a section of the rail, with the section's reason for existing behind it. */
function RailSection({ title, hint }: { title: string; hint: string }) {
  return (
    <div style={{ padding: '10px 12px 4px', borderTop: '1px solid rgba(5,5,5,0.06)' }}>
      <Tooltip title={hint}>
        <Typography.Text type="secondary" style={{ fontSize: 11, letterSpacing: 0.4 }}>
          {title.toUpperCase()}
        </Typography.Text>
      </Tooltip>
    </div>
  );
}

/**
 * The icon for a server folder, taken from IMAP's SPECIAL-USE rather than from its name —
 * the names are in whatever language the mailbox was set up in, and on this one the four
 * system folders are Черновики, Отправленные, Спам and Корзина.
 */
function serverIcon(f: MailServerFolder) {
  switch (f.specialUse) {
    case 'INBOX':
      return <InboxOutlined />;
    case 'SENT':
      return <SendOutlined />;
    case 'DRAFTS':
      return <EditOutlined />;
    case 'JUNK':
      return <StopOutlined />;
    case 'TRASH':
      return <DeleteOutlined />;
    default:
      return <FolderOutlined />;
  }
}

/**
 * The flat list the API returns, assembled into the tree the rail draws.
 *
 * Parents are matched by name, and a folder whose parent is missing from the list — an
 * unselectable branch the server declined to report, say — is hoisted to the top rather than
 * dropped. A folder that cannot be drawn is a folder whose mail cannot be reached.
 */
function toTree(folders: MailServerFolder[]) {
  const byName = new Map(folders.map((f) => [f.fullName, f]));
  const children = new Map<string, MailServerFolder[]>();
  const roots: MailServerFolder[] = [];

  for (const f of folders) {
    const parent = f.parentName && byName.has(f.parentName) ? f.parentName : null;
    if (parent) {
      children.set(parent, [...(children.get(parent) ?? []), f]);
    } else {
      roots.push(f);
    }
  }

  const node = (f: MailServerFolder): DataNode => ({
    key: f.fullName,
    // A folder that holds no mail of its own is a branch of the tree, not a place to look:
    // letting it be picked would show an empty table and read as a folder that had lost its
    // contents.
    selectable: f.selectable,
    icon: serverIcon(f),
    title: <ServerRailLabel folder={f} />,
    children: (children.get(f.fullName) ?? []).map(node),
  });
  return roots.map(node);
}

/**
 * One server folder in the rail: its name, the unread badge, and — behind the tooltip — how
 * much of it has actually been synced.
 *
 * The two numbers are worth keeping apart. The badge counts unread mail the app holds; the
 * server's own count is what the folder holds in the mailbox. A first sync reaches back
 * thirty days and drains a backlog over several polls, so "26 there, 18 here" is a normal
 * state, and a rail that quietly showed only the second number would be lying by omission.
 */
function ServerRailLabel({ folder }: { folder: MailServerFolder }) {
  const behind =
    folder.serverTotal != null && folder.serverTotal > folder.total
      ? folder.serverTotal - folder.total
      : 0;

  const hint = [
    `${folder.total} synced${behind ? ` of ${folder.serverTotal} in the mailbox` : ''}`,
    folder.lastStatus === 'FAILED' ? `Last sync failed: ${folder.lastError}` : null,
    folder.lastSyncAt
      ? `Last read ${dayjs(folder.lastSyncAt).format('YYYY-MM-DD HH:mm')}`
      : 'Not read yet',
    folder.present ? null : 'No longer on the server — kept for the mail already synced',
  ]
    .filter(Boolean)
    .join(' · ');

  return (
    <Tooltip title={hint} mouseEnterDelay={0.4}>
      <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
        <span
          style={{
            flex: 1,
            width: 0,
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            textDecoration: folder.present ? undefined : 'line-through',
          }}
        >
          {folder.displayName}
        </span>
        {folder.lastStatus === 'FAILED' && <Tag color="error">!</Tag>}
        {folder.unread > 0 && <Badge count={folder.unread} size="small" overflowCount={999} />}
      </span>
    </Tooltip>
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
          <Tooltip title="Every folder in the mailbox is mirrored; the rail on the left is that tree">
            <Tag>
              {status.folderCount} folder{status.folderCount === 1 ? '' : 's'}
            </Tag>
          </Tooltip>
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
