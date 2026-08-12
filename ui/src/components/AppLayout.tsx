import { ReactNode } from 'react';
import { Badge, Layout, Menu, Typography } from 'antd';
import {
  DashboardOutlined,
  ContainerOutlined,
  BankOutlined,
  TeamOutlined,
  UnorderedListOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import { useEmailList } from '../emailList/store';

const { Sider, Header, Content } = Layout;

// No '/contacts': contacts live inside People now, grouped under the person who owns them.
const KEYS = ['/', '/vessels', '/companies', '/people', '/email-list', '/circulars'];

export default function AppLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const { entries } = useEmailList();
  const selected = KEYS.includes(location.pathname) ? location.pathname : '/';

  const items = [
    { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
    { key: '/vessels', icon: <ContainerOutlined />, label: 'Vessels' },
    { key: '/companies', icon: <BankOutlined />, label: 'Companies' },
    { key: '/people', icon: <TeamOutlined />, label: 'People & contacts' },
    {
      key: '/email-list',
      icon: <UnorderedListOutlined />,
      label: (
        <span>
          Email list
          {entries.length > 0 && (
            <Badge count={entries.length} size="small" style={{ marginInlineStart: 8 }} />
          )}
        </span>
      ),
    },
    { key: '/circulars', icon: <SendOutlined />, label: 'Circulars' },
  ];

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth="0">
        <div style={{ color: '#fff', padding: 16, fontWeight: 600, fontSize: 16 }}>
          ⚓ Chartering
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selected]}
          items={items}
          onClick={(e) => navigate(e.key)}
        />
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
