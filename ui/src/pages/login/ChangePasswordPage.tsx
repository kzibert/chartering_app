import { Button, Card, Typography } from 'antd';
import { useQueryClient } from '@tanstack/react-query';
import ChangePasswordForm from '../../auth/ChangePasswordForm';
import { clearToken } from '../../auth/store';

/**
 * The whole app for an account still holding a password an administrator chose — a new
 * account, or one just reset. The server answers nothing else to such a session, so there is
 * nothing to show behind this; it is a screen of its own, laid out like the login it follows.
 */
export default function ChangePasswordPage({ username }: { username: string }) {
  const queryClient = useQueryClient();
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
      <Card style={{ width: '100%', maxWidth: 400, boxShadow: '0 2px 12px rgba(0,0,0,0.08)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <Typography.Title level={3} style={{ marginBottom: 4 }}>
            Choose your password
          </Typography.Title>
          <Typography.Text type="secondary">
            {username}, the password you logged in with was set by an administrator. Choose one
            only you know to continue.
          </Typography.Text>
        </div>
        <ChangePasswordForm />
        <Button
          type="link"
          block
          style={{ marginTop: 8 }}
          onClick={() => {
            clearToken();
            queryClient.clear();
          }}
        >
          Log out
        </Button>
      </Card>
    </div>
  );
}
