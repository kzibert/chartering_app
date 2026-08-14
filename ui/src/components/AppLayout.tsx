import { ReactNode } from 'react';
import { Badge, Layout, Menu, Typography } from 'antd';
import {
  DashboardOutlined,
  ContainerOutlined,
  BankOutlined,
  TeamOutlined,
  UnorderedListOutlined,
  SendOutlined,
  SettingOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useCurrentList } from '../circulations/store';

const { Sider, Header, Content } = Layout;

// No '/contacts': contacts live inside People now, grouped under the person who owns them.
const KEYS = [
  '/', '/vessels', '/companies', '/people', '/circulation-lists', '/circulars', '/settings',
];

export default function AppLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { entries } = useCurrentList();
  const selected = KEYS.includes(location.pathname) ? location.pathname : '/';

  const items = [
    { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
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
  ];

  // Settings sits in its own menu pinned to the foot of the sidebar rather than trailing
  // the list: it is not a place you work, it is where you go when something about how the
  // work behaves needs changing, and parking it at the bottom keeps it out of the way of
  // the tabs that are used all day. Its own Menu is what allows that — one Menu cannot
  // have an item that floats away from its siblings.
  const settingsItems = [
    { key: '/settings', icon: <SettingOutlined />, label: 'Settings' },
  ];

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
        <Header style={{ background: '#fff', paddingInline: 24 }}>
          <Typography.Title level={4} style={{ margin: '16px 0' }}>
            Maritella chartering application
          </Typography.Title>
        </Header>
        <Content style={{ margin: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
