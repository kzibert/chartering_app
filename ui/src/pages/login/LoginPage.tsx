import { useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { authApi, type LoginRequest } from '../../api/auth';
import { setToken } from '../../auth/store';

/**
 * The whole app when there is no token. Not a route: there is nothing to navigate to while
 * logged out, and making it one would mean guarding every other route and remembering where
 * the user was going. App.tsx swaps this in for the entire layout instead, so a session that
 * ends mid-page leaves the address bar alone — logging back in returns to the same screen.
 */
export default function LoginPage() {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onFinish = async (values: LoginRequest) => {
    setSubmitting(true);
    setError(null);
    try {
      const res = await authApi.login(values);
      // Setting the token is the navigation: App.tsx is subscribed to it.
      setToken(res.token);
    } catch (e: any) {
      // The client interceptor deliberately stays quiet for this call so the message lands
      // here, under the fields, instead of in a notification that covers them.
      setError(
        e?.response?.data?.message ??
          (e?.response ? 'Login failed.' : 'Cannot reach the server.'),
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f0f2f5',
        padding: 16,
      }}
    >
      {/* maxWidth rather than width: on a 360px phone a 380px card is wider than the
          screen it is centred in, and the login box is the first thing anyone sees. */}
      <Card style={{ width: '100%', maxWidth: 380, boxShadow: '0 2px 12px rgba(0,0,0,0.08)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            ⚓ Chartering
          </Typography.Title>
          <Typography.Text type="secondary">Sign in to continue</Typography.Text>
        </div>

        {error && (
          <Alert
            type="error"
            message={error}
            showIcon
            style={{ marginBottom: 16 }}
          />
        )}

        <Form layout="vertical" onFinish={onFinish} requiredMark={false} disabled={submitting}>
          <Form.Item
            name="username"
            label="Username"
            rules={[{ required: true, message: 'Username is required' }]}
          >
            <Input
              prefix={<UserOutlined />}
              autoFocus
              autoComplete="username"
              placeholder="admin"
            />
          </Form.Item>
          <Form.Item
            name="password"
            label="Password"
            rules={[{ required: true, message: 'Password is required' }]}
          >
            <Input.Password
              prefix={<LockOutlined />}
              autoComplete="current-password"
              placeholder="••••••••"
            />
          </Form.Item>
          <Form.Item style={{ marginBottom: 0 }}>
            <Button type="primary" htmlType="submit" block loading={submitting}>
              Log in
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
