import { useMemo, useState } from 'react';
import {
  Alert,
  App,
  Badge,
  Button,
  Card,
  Checkbox,
  Divider,
  Dropdown,
  Empty,
  Input,
  Menu,
  Popconfirm,
  Drawer,
  Skeleton,
  Space,
  Tree,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  BankOutlined,
  CheckCircleOutlined,
  DeleteOutlined,
  EditOutlined,
  FilterOutlined,
  FolderOpenOutlined,
  FolderOutlined,
  InboxOutlined,
  MailOutlined,
  PaperClipOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
  SyncOutlined,
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
import { useQuery } from '@tanstack/react-query';
import { circulationsApi } from '../../api/circulations';
import { SendingTodayTags } from '../../components/SendingToday';
import { usePersistedState } from '../../components/usePersistedState';
import { useTableControls } from '../../components/useTableControls';
import ResponsiveTable from '../../components/ResponsiveTable';
import { useIsMobile } from '../../responsive/useIsMobile';
import CompanyDrawer from '../companies/CompanyDrawer';
import CompanyForm from '../companies/CompanyForm';
import MessageDrawer from './MessageDrawer';
import FoldersRulesModal from './FoldersRulesModal';
import type {
  CirculationToday,
  CompanyResponse,
  MailMessage,
  MailServerFolder,
  MailboxFilter,
  MailboxScope,
} from '../../api/types';

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
  // The company being edited, and the form's own open flag — the same pair the
  // Companies tab keeps. Separate from `companyId` because the form edits the record
  // the drawer is showing while that drawer stays open behind it.
  const [editingCompany, setEditingCompany] = useState<CompanyResponse | null>(null);
  const [companyFormOpen, setCompanyFormOpen] = useState(false);
  const [manageOpen, setManageOpen] = useState(false);
  const [picked, setPicked] = useState<number[]>([]);
  const isMobile = useIsMobile();
  // The rail is two folder trees and does not fold down to anything smaller; on a phone it
  // is a drawer, opened by a button that names the folder you are in.
  const [railOpen, setRailOpen] = useState(false);

  const table = useTableControls({ size: 25, sort: 'receivedAt,desc' }, 'mailbox');
  const folders = useMailFolders();
  const serverFolders = useMailServerFolders();
  const status = useMailboxStatus();
  const sync = useMailboxSync();
  const { setReadBulk, markAllRead, moveBulk } = useMailMessageMutations();
  // The same key the Circulars and Settings tabs read the day's volume from, so a reply
  // sent here moves the number there without either tab counting anything itself.
  const todayQ = useQuery({ queryKey: ['circulations', 'today'], queryFn: circulationsApi.today });

  /**
   * Which mail is being looked at — the rail's folder and the search box, and nothing about
   * the page or the unread checkbox. Kept apart from the query below because it is also what
   * "Mark all read" acts on, and that action has to mean the same thing on page four as on
   * page one.
   */
  const scope: MailboxScope = useMemo(
    () => ({
      search: filters.search || undefined,
      searchBody: filters.searchBody || undefined,
      unfiled: filters.scope === 'inbox' ? true : undefined,
      folderId: typeof filters.scope === 'number' ? filters.scope : undefined,
      imapFolder: serverScope(filters.scope),
    }),
    [filters.search, filters.searchBody, filters.scope],
  );

  const query: MailboxFilter = useMemo(
    () => ({
      ...scope,
      read: filters.unreadOnly ? false : undefined,
      page: table.state.page,
      size: table.state.size,
      sort: table.state.sort ?? 'receivedAt,desc',
    }),
    [scope, filters.unreadOnly, table.state],
  );
  const messages = useMailMessages(query);
  const rows = messages.data?.content ?? [];

  const update = (patch: Partial<Filters>) => {
    setFilters((f) => ({ ...f, ...patch }));
    setPicked([]);
    table.resetPage();
    // Picking a folder is the one thing the phone's rail drawer is open for.
    setRailOpen(false);
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

  /** What the rail is showing, in the words the rail shows it in — the confirm quotes it. */
  const scopeName =
    filters.scope === 'all'
      ? 'All mail'
      : filters.scope === 'inbox'
        ? 'Unfiled'
        : typeof filters.scope === 'number'
          ? (named.find((f) => f.id === filters.scope)?.name ?? 'this folder')
          : (serverNames.get(filters.scope.server) ?? filters.scope.server);

  const markAll = () =>
    markAllRead.mutate(scope, {
      onSuccess: (marked) => {
        if (marked === 0) message.info('Nothing unread here');
        else message.success(`Marked ${marked} message${marked === 1 ? '' : 's'} read`);
        setPicked([]);
      },
    });

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

  /**
   * The search box, declared once and rendered in one of two places.
   *
   * On a desktop it sits in the toolbar row with the checkboxes, at a width that fits the
   * placeholder. On a phone that same row cannot hold it: 420px of search plus the folder
   * button overflows a 390px screen, and the Search button — the one part of the control
   * that has to stay reachable — is what hangs off the right edge. Capping it in CSS does
   * not help, because a percentage width inside antd's Space resolves against an item that
   * is itself sized by its content. A row of its own is what actually fixes it, and it
   * reads better besides: the search is the thing this tab is used for.
   */
  const searchBox = (
    <Input.Search
      allowClear
      value={typed}
      onChange={(e) => setTyped(e.target.value)}
      onSearch={runSearch}
      placeholder={isMobile ? 'Search mail…' : 'Address, person, company, or words from the subject…'}
      style={{ width: isMobile ? '100%' : 420 }}
      enterButton
    />
  );

  /**
   * The two folder trees. A Card in the page on a desktop, a drawer on a phone: this is
   * 250px of tree that does not fold down to anything smaller, and stacking it above the
   * messages would put a screenful of folders between the user and their mail every time
   * the tab is opened.
   */
  const rail = (
    <>
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
    </>
  );

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <SyncBar
        status={status.data}
        today={todayQ.data}
        onSync={() => sync.mutate(undefined, { onSuccess: () => message.info('Fetching mail…') })}
        syncing={status.data?.syncing || sync.isPending}
      />

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        {!isMobile && (
          <Card
            size="small"
            style={{ width: 250, flex: '0 0 250px' }}
            styles={{ body: { padding: 0 } }}
          >
            {rail}
          </Card>
        )}

        <Card size="small" style={{ flex: 1, minWidth: 0 }}>
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            {/* Full width, and above everything else — see searchBox. */}
            {isMobile && searchBox}
            {/* The filters on the left and the one action on the right, rather than all of
                it in one Space: "Mark all read" acts on everything these controls select,
                so it belongs at the end of the row that defines it — and away from the
                checkboxes, where a mis-aimed click would be expensive. */}
            <div
              style={{
                display: 'flex',
                gap: 8,
                alignItems: 'center',
                justifyContent: 'space-between',
                flexWrap: 'wrap',
              }}
            >
              <Space wrap>
                {isMobile && (
                  <Button icon={<FilterOutlined />} onClick={() => setRailOpen(true)}>
                    {scopeName}
                  </Button>
                )}
                {!isMobile && searchBox}
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

              <Popconfirm
                title="Mark everything here as read"
                description={
                  <span style={{ display: 'inline-block', maxWidth: 280 }}>
                    Every unread message in <strong>{scopeName}</strong>
                    {filters.search ? <> matching “{filters.search}”</> : null} — not only the
                    ones on this page.
                  </span>
                }
                okText="Mark read"
                cancelText="Cancel"
                okButtonProps={{ loading: markAllRead.isPending }}
                onConfirm={markAll}
              >
                <Button
                  icon={<CheckCircleOutlined />}
                  loading={markAllRead.isPending}
                  disabled={(messages.data?.totalElements ?? 0) === 0}
                >
                  Mark all read
                </Button>
              </Popconfirm>
            </div>

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

            <ResponsiveTable<MailMessage>
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
              // Sender first, then subject and snippet — the order every mail app on a
              // phone uses, and the order the eye already expects to find them in.
              mobile={{
                title: (m) => (
                  <Space size={6} align="center">
                    {!m.read && <Badge status="processing" />}
                    <Typography.Text strong={!m.read}>
                      {m.fromName || m.fromAddress}
                    </Typography.Text>
                    {m.hasAttachments && <PaperClipOutlined style={{ color: '#8c8c8c' }} />}
                  </Space>
                ),
                subtitle: (m) => (
                  <>
                    <div style={{ ...CLAMP, color: 'rgba(0,0,0,.85)' }}>
                      <Typography.Text strong={!m.read}>
                        {m.subject || '(no subject)'}
                      </Typography.Text>
                    </div>
                    {m.snippet && (
                      <div style={{ ...CLAMP, fontSize: 12, color: '#8c8c8c' }}>{m.snippet}</div>
                    )}
                  </>
                ),
                fields: (m) => [
                  { label: 'Received', value: <RelativeDate value={m.receivedAt} /> },
                  m.companyId
                    ? {
                        label: 'Company',
                        value: (
                          <Typography.Link
                            onClick={(e) => {
                              e.stopPropagation();
                              setCompanyId(m.companyId);
                            }}
                          >
                            {m.companyName}
                          </Typography.Link>
                        ),
                      }
                    : { label: 'Company', value: 'unknown sender' },
                  m.folderName != null && { label: 'Filed in', value: m.folderName },
                  m.imapFolder != null && {
                    label: 'Mailbox folder',
                    value: serverNames.get(m.imapFolder) ?? m.imapFolder,
                  },
                ],
              }}
              mobileSort={[{ field: 'receivedAt', label: 'Received' }]}
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

      <Drawer
        open={isMobile && railOpen}
        onClose={() => setRailOpen(false)}
        placement="left"
        width={280}
        title="Folders"
        styles={{ body: { padding: 0 } }}
      >
        {rail}
      </Drawer>

      <MessageDrawer
        messageId={openId}
        onClose={() => setOpenId(undefined)}
        onOpenCompany={(id) => setCompanyId(id)}
      />
      {/* Three layers deep by the time the form is open — message, company, form — and
          that is the point: the correction a message prompts ("they have moved to Piraeus")
          is made without losing the mail that prompted it, and closing each layer walks
          back to the one underneath.

          They stack in that order for a reason worth knowing, because it is not z-index.
          antd only computes one for an overlay rendered *inside* another; these three are
          siblings, so all three take the stylesheet's 1000 and DOM order decides. Each
          overlay's portal is created when it opens and removed again when it closes
          (rc-portal's autoDestroy), so the last one opened is the last node in the body —
          which is this one. Mounting the form inside CompanyDrawer instead would be the
          belt-and-braces version and costs the drawer a prop it does not otherwise need;
          the Companies tab keeps the same pair side by side. */}
      <CompanyDrawer
        companyId={companyId}
        initialTab="contacts"
        onClose={() => setCompanyId(undefined)}
        onEdit={(c) => {
          setEditingCompany(c);
          setCompanyFormOpen(true);
        }}
      />
      <CompanyForm
        open={companyFormOpen}
        editing={editingCompany}
        onClose={() => setCompanyFormOpen(false)}
        // The drawer behind the form is showing the company that was just deleted. The
        // message stays open underneath — deleting a company only unlinks its mail.
        onDeleted={() => setCompanyId(undefined)}
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

/** The muted, small type the bar's supporting facts are set in. */
const BAR_NOTE: React.CSSProperties = { fontSize: 12 };

/**
 * What the sync is doing, and why it is not doing it.
 *
 * <p>Shown above the mail rather than tucked into a settings screen: an inbox that stopped
 * syncing three days ago looks exactly like an inbox where nothing has arrived, and the
 * whole tab is misleading until that is said out loud.
 *
 * <p>A healthy mailbox says so in a strip built like the rest of the tab — the same Card as
 * the rail and the table, one line of quiet type, the fetch control at the end of it. It was
 * a green success Alert, which is the wrong shape twice over: an Alert is a thing that has
 * happened and wants reading, and "the mail is arriving normally" is the permanent state of
 * this tab, so it shouted a colour at the top of the page all day and left Fetch now hanging
 * off the side of it as an alert's afterthought rather than as the tab's own control.
 *
 * <p>The Alerts are kept for the two states that really are alerts — not configured, and the
 * last pass failed — and now sit under the strip, so the fetch button is in one place whether
 * or not anything is wrong.
 */
function SyncBar({
  status,
  today,
  onSync,
  syncing,
}: {
  today?: CirculationToday;
  status?: import('../../api/types').MailboxStatus;
  onSync: () => void;
  syncing: boolean;
}) {
  if (!status) return null;

  const failed = status.lastStatus === 'FAILED';
  const dot = !status.configured ? 'default' : syncing ? 'processing' : failed ? 'error' : 'success';
  const every = Math.round(status.pollIntervalMs / 60000);

  return (
    // A column rather than a fragment: the strip and an alert under it are two blocks, and
    // the page's own Space cannot see inside a component to put a gap between them.
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card size="small" styles={{ body: { padding: '6px 12px' } }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 12,
            rowGap: 6,
            flexWrap: 'wrap',
            minWidth: 0,
          }}
        >
          {/* The state of the mailbox as a dot beside its own address: it is either fine,
              working, or broken, and that is one glyph's worth of information. */}
          <Badge status={dot} />
          <Typography.Text strong ellipsis style={{ maxWidth: 240 }}>
            {status.username ?? 'Mailbox'}
          </Typography.Text>

          <Divider type="vertical" style={{ margin: 0, height: 16 }} />

          <Tooltip title="Every folder in the mailbox is mirrored; the rail on the left is that tree">
            <Typography.Text type="secondary" style={BAR_NOTE}>
              {status.folderCount} folder{status.folderCount === 1 ? '' : 's'}
            </Typography.Text>
          </Tooltip>
          <Typography.Text type="secondary" style={BAR_NOTE}>
            {status.unread} unread of {status.totalMessages}
          </Typography.Text>

          {/* The day's outgoing volume, on the tab where the replies that swell it are
              written. The same tags the Circulars and Settings tabs show, from the same
              query — three places quoting one number rather than three counting it. */}
          <Divider type="vertical" style={{ margin: 0, height: 16 }} />
          <SendingTodayTags today={today} />

          {/* Pushes the fetch control to the far end, where the eye is not passing over it
              on the way to the mail. */}
          <span style={{ flex: 1, minWidth: 24 }} />

          <Typography.Text type="secondary" style={BAR_NOTE}>
            {syncing ? (
              'reading the mailbox…'
            ) : status.lastSyncAt ? (
              <>
                fetched <RelativeDate value={status.lastSyncAt} /> · {status.lastStored} new of{' '}
                {status.lastFetched} read
              </>
            ) : (
              'not fetched yet'
            )}
          </Typography.Text>

          <Tooltip
            title={
              status.configured
                ? `Read the mailbox now. It is read on its own every ${every} min.`
                : 'Not configured — see below'
            }
          >
            {/* Ghost rather than solid: it is the tab's own control and should look like one,
                but nothing here needs doing — the mail arrives without it being pressed. The
                icon spins in place instead of the button going into antd's loading state,
                which would swap the icon for a spinner and shift the label as it did so. */}
            <Button
              type="primary"
              ghost
              icon={<SyncOutlined spin={syncing} />}
              disabled={!status.configured || syncing}
              onClick={onSync}
            >
              {syncing ? 'Fetching…' : 'Fetch now'}
            </Button>
          </Tooltip>
        </div>
      </Card>

      {!status.configured && (
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
      )}

      {status.configured && failed && (
        <Alert
          type="error"
          showIcon
          message={`Last sync failed — ${dayjs(status.lastSyncAt).format('YYYY-MM-DD HH:mm')}`}
          description={status.lastError}
        />
      )}
    </div>
  );
}
