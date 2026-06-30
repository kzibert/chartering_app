import { Button, Tooltip } from 'antd';
import { StopOutlined } from '@ant-design/icons';

/**
 * Ban / unban toggle for a Russian-rooted entity (company / vessel / contact).
 * Banned entities are excluded from filters by default. Presentational only — the
 * caller wires the actual mutation.
 */
export default function BanButton({
  banned,
  loading,
  onToggle,
  size = 'middle',
}: {
  banned: boolean;
  loading?: boolean;
  onToggle: (banned: boolean) => void;
  size?: 'small' | 'middle';
}) {
  return (
    <Tooltip
      title={
        banned
          ? 'Banned (Russian-rooted) — hidden from filters by default. Click to unban.'
          : 'Ban as Russian-rooted — hide from filters by default.'
      }
    >
      <Button
        size={size}
        danger={!banned}
        loading={loading}
        icon={<StopOutlined />}
        onClick={() => onToggle(!banned)}
      >
        {banned ? 'Unban' : 'Ban'}
      </Button>
    </Tooltip>
  );
}
