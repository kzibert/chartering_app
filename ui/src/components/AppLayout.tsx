import { ReactNode } from 'react';
import { Layout, Menu, Typography } from 'antd';
import {
  DashboardOutlined,
  ContainerOutlined,
  BankOutlined,
  TeamOutlined,
  MailOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';

const { Sider, Header, Content } = Layout;

const ITEMS = [
  { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
  { key: '/vessels', icon: <ContainerOutlined />, label: 'Vessels' },
  { key: '/companies', icon: <BankOutlined />, label: 'Companies' },
  { key: '/people', icon: <TeamOutlined />, label: 'People' },
  { key: '/contacts', icon: <MailOutlined />, label: 'Contacts' },
];

export default function AppLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const selected = ITEMS.some((i) => i.key === location.pathname) ? location.pathname : '/';

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
          items={ITEMS}
          onClick={(e) => navigate(e.key)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', paddingInline: 24 }}>
          <Typography.Title level={4} style={{ margin: '16px 0' }}>
            Vessel Console — chartering
          </Typography.Title>
        </Header>
        <Content style={{ margin: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
