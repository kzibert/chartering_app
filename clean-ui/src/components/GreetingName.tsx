import { Space, Typography } from 'antd';
import { CheckOutlined, UserOutlined } from '@ant-design/icons';

interface Props {
  title?: string;
  name?: string;
  /** Ant Typography text type for the displayed name. */
  type?: 'secondary' | 'success' | 'warning' | 'danger';
}

/**
 * Renders a person's greeting as "Title Name" (e.g. "Mr. Fuat") with two copy
 * affordances: the clipboard icon copies WITH the title, and the person icon
 * copies the name only (shown only when a title is present).
 */
export default function GreetingName({ title, name, type }: Props) {
  if (!name) return null;
  const full = [title, name].filter(Boolean).join(' ');
  return (
    <Space size={2} wrap={false}>
      <Typography.Text type={type}>{full}</Typography.Text>
      <Typography.Text copyable={{ text: full, tooltips: ['Copy with title', 'Copied'] }} />
      {title && (
        <Typography.Text
          type="secondary"
          copyable={{
            text: name,
            icon: [<UserOutlined key="copy" />, <CheckOutlined key="copied" />],
            tooltips: ['Copy name only', 'Copied'],
          }}
        />
      )}
    </Space>
  );
}
