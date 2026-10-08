import { useState } from 'react';
import { Alert, Button, Form, Input } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import { authApi } from '../api/auth';
import { setToken } from './store';

/** The server's own rule (PasswordPolicy) — repeated here only to say it before the round trip. */
export const MIN_PASSWORD_LENGTH = 10;

interface Values {
  currentPassword: string;
  newPassword: string;
  confirm: string;
}

/**
 * Replace the logged-in account's password.
 *
 * Success swaps the token rather than keeping the old one: the server revokes every token the
 * account held, this one included, and hands back a fresh one. Storing it remounts the
 * authenticated app (App.tsx keys it on the token), which is also what lifts the forced
 * change-password screen once the server stops saying one is required.
 */
export default function ChangePasswordForm({ onDone }: { onDone?: () => void }) {
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onFinish = async (values: Values) => {
    setSubmitting(true);
    setError(null);
    try {
      const res = await authApi.changePassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
      setToken(res.token);
      onDone?.();
    } catch (e: any) {
      setError(e?.response?.data?.message ?? 'The password could not be changed.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      {error && <Alert type="error" message={error} showIcon style={{ marginBottom: 16 }} />}
      <Form layout="vertical" onFinish={onFinish} requiredMark={false} disabled={submitting}>
        <Form.Item
          name="currentPassword"
          label="Current password"
          rules={[{ required: true, message: 'Current password is required' }]}
        >
          <Input.Password prefix={<LockOutlined />} autoComplete="current-password" autoFocus />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label="New password"
          extra={`At least ${MIN_PASSWORD_LENGTH} characters. A phrase is easier to remember than symbols.`}
          rules={[
            { required: true, message: 'New password is required' },
            { min: MIN_PASSWORD_LENGTH, message: `At least ${MIN_PASSWORD_LENGTH} characters` },
          ]}
        >
          <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirm"
          label="Repeat new password"
          dependencies={['newPassword']}
          rules={[
            { required: true, message: 'Repeat the new password' },
            ({ getFieldValue }) => ({
              validator: (_, value) =>
                !value || value === getFieldValue('newPassword')
                  ? Promise.resolve()
                  : Promise.reject(new Error('The two passwords differ')),
            }),
          ]}
        >
          <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
        </Form.Item>
        <Form.Item style={{ marginBottom: 0 }}>
          <Button type="primary" htmlType="submit" block loading={submitting}>
            Change password
          </Button>
        </Form.Item>
      </Form>
    </>
  );
}
