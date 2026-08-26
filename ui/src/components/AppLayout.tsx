import { useEffect, useState, type ReactNode } from 'react';
import { Badge, Button, Drawer, Dropdown, Layout, Menu, Space, Typography } from 'antd';
import {
  DashboardOutlined,
  ContainerOutlined,
  InboxOutlined,
  BankOutlined,
  TeamOutlined,
  UnorderedListOutlined,
  SendOutlined,
  MailOutlined,
  SettingOutlined,
  LogoutOutlined,
  UserOutlined,
  MenuOutlined,
  EllipsisOutlined,
  HistoryOutlined,
  ExperimentOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useCurrentList } from '../circulations/store';
import { useMailboxStatus } from '../mailbox/store';
import { useAnalysisStatus } from '../analysis/store';
import { clearToken } from '../auth/store';
import { useIsMobile } from '../responsive/useIsMobile';

const { Sider, Header, Content } = Layout;

// No '/contacts': contacts live inside People now, grouped under the person who owns them.
const KEYS = [
  '/', '/cargoes', '/vessels', '/companies', '/people', '/circulation-lists', '/circulars',
  '/mailbox', '/analysis', '/history', '/settings',
];

/**
 * The four destinations the bottom bar carries on a phone, plus More.
 *
 * They are the four you look something *up* in. Composing a circular, curating a list and
 * changing settings are desk work — they stay one tap away in the More drawer rather than
 * spending a slot in a bar that only has five. Five is the ceiling because a sixth target
 * on a 360px screen is narrower than a fingertip.
 */
const TAB_KEYS = ['/', '/vessels', '/companies', '/people'];

export default function AppLayout({
  children,
  username,
}: {
  children: ReactNode;
  username?: string;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const queryClient = useQueryClient();
  const { entries } = useCurrentList();
  const status = useMailboxStatus();
  // Whether this deployment carries the analysis workbench at all. One cached call — the
  // answer cannot change without the api restarting — and it is what decides whether the
  // tab is in the navigation rather than whether it works when clicked.
  const analysis = useAnalysisStatus();
  const isMobile = useIsMobile();
  const [navOpen, setNavOpen] = useState(false);
  const selected = KEYS.includes(location.pathname) ? location.pathname : '/';
  const unread = status.data?.unread ?? 0;

  // A tap on a nav entry has to close the drawer as well as navigate, or the page you
  // asked for is hidden behind the thing you asked for it from.
  useEffect(() => setNavOpen(false), [location.pathname]);

  const items = [
    { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
    // The trading tabs sit above the record tabs because that is the order of the day:
    // cargoes and positions arrive, they get matched, and the vessel and company records
    // are what you go and read when one of them raises a question.
    { key: '/cargoes', icon: <InboxOutlined />, label: 'Cargoes' },
    { key: '/vessels', icon: <ContainerOutlined />, label: 'Vessels' },
    { key: '/companies', icon: <BankOutlined />, label: 'Companies' },
    { key: '/people', icon: <TeamOutlined />, label: 'People & contacts' },
    {
      key: '/circulation-lists',
      icon: <UnorderedListOutlined />,
      // The badge counts the current list specifically — that is what the Circulars tab
      // will send to, so it is the number worth carrying in the nav.
      label: (
        <span>
          Circulation lists
          {entries.length > 0 && (
            <Badge count={entries.length} size="small" style={{ marginInlineStart: 8 }} />
          )}
        </span>
      ),
    },
    { key: '/circulars', icon: <SendOutlined />, label: 'Circulars' },
    {
      key: '/mailbox',
      icon: <MailOutlined />,
      // Unread mail is the one count in this app that means "somebody is waiting for you",
      // so it is worth carrying in the nav the way the current list's size is. The query is
      // the mailbox status endpoint the tab already polls, so this costs no extra request.
      label: (
        <span>
          Mailbox
          {unread > 0 && <Badge count={unread} size="small" style={{ marginInlineStart: 8 }} />}
        </span>
      ),
    },
    // Local deployments only: ANALYSIS_ENABLED is false on the hosted instance, and an
    // entry that led to a page explaining why it does nothing is worse than no entry. The
    // route still exists, so a bookmarked URL lands on that explanation.
    ...(analysis.data?.enabled
      ? [{ key: '/analysis', icon: <ExperimentOutlined />, label: 'Analysis' }]
      : []),
    // Last in the list, and not in the bottom bar on a phone: the change log is where you
    // go when something is already wrong, not somewhere work gets done. It sits above
    // Settings because it is about the data rather than about the application.
    { key: '/history', icon: <HistoryOutlined />, label: 'History' },
  ];

  // Settings sits in its own menu pinned to the foot of the sidebar rather than trailing
  // the list: it is not a place you work, it is where you go when something about how the
  // work behaves needs changing, and parking it at the bottom keeps it out of the way of
  // the tabs that are used all day. Its own Menu is what allows that — one Menu cannot
  // have an item that floats away from its siblings.
  const settingsItems = [
    { key: '/settings', icon: <SettingOutlined />, label: 'Settings' },
  ];

  /**
   * Logging out is throwing the token away — the server keeps no session to end, so there
   * is nothing to tell it. Clearing the query cache with it matters more than it looks:
   * without that, the cached vessels and mailbox messages would still be sitting in memory
   * behind the login screen and would flash up for a moment on the next login.
   */
  const logout = () => {
    clearToken();
    queryClient.clear();
  };

  if (isMobile) {
    return (
      <MobileLayout
        selected={selected}
        items={items}
        settingsItems={settingsItems}
        navOpen={navOpen}
        setNavOpen={setNavOpen}
        navigate={navigate}
        username={username}
        logout={logout}
        // Everything the More drawer hides that somebody may be waiting on. Without this,
        // unread mail is invisible on a phone until you go looking for it.
        moreDot={unread > 0 || entries.length > 0}
      >
        {children}
      </MobileLayout>
    );
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      {/* Pinned to the viewport, not to the page: without this the sider grows to the
          height of whatever table is on screen, and "the bottom" ends up thousands of
          pixels down. alignSelf matters — a flex child stretches to the row's height by
          default, which leaves sticky nothing to move within. */}
      <Sider
        breakpoint="lg"
        collapsedWidth="0"
        style={{ position: 'sticky', top: 0, alignSelf: 'flex-start', height: '100vh' }}
      >
        {/* antd gives .ant-layout-sider-children height:100%, so a flex column here fills
            the sider and lets the main menu take the slack above the pinned footer. */}
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
          <div style={{ color: '#fff', padding: 16, fontWeight: 600, fontSize: 16 }}>
            ⚓ Chartering
          </div>
          {/* minHeight:0 lets this shrink and scroll instead of pushing Settings off the
              bottom once the list outgrows a short window. */}
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selected]}
            items={items}
            onClick={(e) => navigate(e.key)}
            style={{ flex: 1, minHeight: 0, overflowY: 'auto', borderInlineEnd: 0 }}
          />
          <Menu
            theme="dark"
            mode="inline"
            selectedKeys={[selected]}
            items={settingsItems}
            onClick={(e) => navigate(e.key)}
            style={{ borderInlineEnd: 0, borderTop: '1px solid rgba(255,255,255,0.12)' }}
          />
        </div>
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            paddingInline: 24,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 16,
          }}
        >
          <Typography.Title level={4} style={{ margin: 0 }}>
            Maritella chartering application
          </Typography.Title>
          <Space size="middle">
            {username && (
              <Typography.Text type="secondary">
                <UserOutlined /> {username}
              </Typography.Text>
            )}
            <Button icon={<LogoutOutlined />} onClick={logout}>
              Log out
            </Button>
          </Space>
        </Header>
        <Content style={{ margin: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}

type NavItem = { key: string; icon: ReactNode; label: ReactNode };

/**
 * The phone shell: a short header, a bottom tab bar, and the full menu in a drawer.
 *
 * The sider is not merely narrowed. antd's `breakpoint="lg"` already collapses it to a
 * zero-width rail with a floating toggle, and that is a desktop affordance shrunk down —
 * the toggle lands under the header, the tray covers the page, and the thing you use forty
 * times a day takes two taps. A bottom bar puts the four destinations that matter within
 * reach of a thumb, which is the half of the screen a phone is actually operated from.
 */
function MobileLayout({
  children,
  selected,
  items,
  settingsItems,
  navOpen,
  setNavOpen,
  navigate,
  username,
  logout,
  moreDot,
}: {
  children: ReactNode;
  selected: string;
  items: NavItem[];
  settingsItems: NavItem[];
  navOpen: boolean;
  setNavOpen: (open: boolean) => void;
  navigate: (to: string) => void;
  username?: string;
  logout: () => void;
  moreDot: boolean;
}) {
  const tabs = TAB_KEYS.map((k) => items.find((i) => i.key === k)!).filter(Boolean);

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header
        style={{
          background: '#fff',
          paddingInline: 12,
          height: 56,
          lineHeight: '56px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          position: 'sticky',
          top: 0,
          zIndex: 10,
          borderBottom: '1px solid rgba(5,5,5,0.06)',
        }}
      >
        <Button
          type="text"
          icon={<MenuOutlined />}
          aria-label="Menu"
          onClick={() => setNavOpen(true)}
        />
        {/* The full product name does not fit beside two buttons at this width, and a
            title that ellipsises to "Maritella charteri…" is worse than the short one. */}
        <Typography.Text strong style={{ fontSize: 16 }}>
          ⚓ Chartering
        </Typography.Text>
        <Dropdown
          trigger={['click']}
          menu={{
            items: [
              ...(username
                ? [{ key: 'who', icon: <UserOutlined />, label: username, disabled: true }]
                : []),
              { key: 'logout', icon: <LogoutOutlined />, label: 'Log out' },
            ],
            onClick: ({ key }) => key === 'logout' && logout(),
          }}
        >
          <Button type="text" icon={<UserOutlined />} aria-label="Account" />
        </Dropdown>
      </Header>

      {/* The bar is fixed, so the page has to end above it rather than under it. 64 is the
          bar; the safe-area inset is the home indicator on iPhones and 0 everywhere else. */}
      <Content
        style={{
          margin: 12,
          paddingBottom: 'calc(64px + env(safe-area-inset-bottom, 0px))',
        }}
      >
        {children}
      </Content>

      <Drawer
        open={navOpen}
        onClose={() => setNavOpen(false)}
        placement="left"
        width={260}
        title="⚓ Chartering"
        styles={{ body: { padding: 0 } }}
      >
        <Menu
          mode="inline"
          selectedKeys={[selected]}
          items={items}
          onClick={(e) => navigate(e.key)}
          style={{ borderInlineEnd: 0 }}
        />
        <Menu
          mode="inline"
          selectedKeys={[selected]}
          items={settingsItems}
          onClick={(e) => navigate(e.key)}
          style={{ borderInlineEnd: 0, borderTop: '1px solid rgba(5,5,5,0.06)' }}
        />
      </Drawer>

      <nav className="mobile-tabbar">
        {tabs.map((t) => (
          <TabButton
            key={t.key}
            icon={t.icon}
            label={TAB_LABELS[t.key] ?? String(t.key)}
            active={selected === t.key}
            onClick={() => navigate(t.key)}
          />
        ))}
        <TabButton
          icon={
            <Badge dot={moreDot} offset={[2, 0]}>
              <EllipsisOutlined />
            </Badge>
          }
          label="More"
          active={!TAB_KEYS.includes(selected)}
          onClick={() => setNavOpen(true)}
        />
      </nav>
    </Layout>
  );
}

/** Short enough to fit a fifth of a phone's width; the drawer carries the full names. */
const TAB_LABELS: Record<string, string> = {
  '/': 'Home',
  '/vessels': 'Vessels',
  '/companies': 'Companies',
  '/people': 'People',
};

function TabButton({
  icon,
  label,
  active,
  onClick,
}: {
  icon: ReactNode;
  label: string;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      style={{
        appearance: 'none',
        background: 'none',
        border: 'none',
        // 52 keeps the whole control at the ~44px minimum a fingertip needs, label included.
        padding: '6px 0 4px',
        minHeight: 52,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: 2,
        cursor: 'pointer',
        color: active ? '#1677ff' : 'rgba(0,0,0,0.55)',
        fontSize: 11,
      }}
    >
      <span style={{ fontSize: 18, lineHeight: 1 }}>{icon}</span>
      <span style={{ whiteSpace: 'nowrap' }}>{label}</span>
    </button>
  );
}
