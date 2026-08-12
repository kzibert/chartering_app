import { useEffect, useState, type ReactNode } from 'react';
import { Button, Space } from 'antd';
import { EditOutlined } from '@ant-design/icons';

/**
 * Edit-mode flag that switches itself back off when `resetKey` changes — drawers stay
 * mounted while you move between records, so without this you would silently still be
 * in edit mode on the next vessel/company you open.
 */
export function useEditMode(resetKey: unknown) {
  const [editing, setEditing] = useState(false);
  useEffect(() => setEditing(false), [resetKey]);
  return [editing, setEditing] as const;
}

/**
 * Toggles a list between read-only browsing and managing its rows. Keeping the
 * add/edit/delete controls behind it keeps the default view uncluttered and makes
 * writes deliberate.
 *
 * Shared by the company drawer's Vessels/People/Contacts tabs and the vessel
 * drawer's owner contacts, so every list behaves the same way.
 */
export default function EditToolbar({
  editing,
  onToggle,
  children,
}: {
  editing: boolean;
  onToggle: (on: boolean) => void;
  /** Controls shown only while editing — typically the "Add …" button(s). */
  children?: ReactNode;
}) {
  return (
    <Space style={{ marginBottom: 8 }} wrap>
      <Button
        size="small"
        type={editing ? 'primary' : 'default'}
        icon={<EditOutlined />}
        onClick={() => onToggle(!editing)}
      >
        {editing ? 'Done' : 'Edit'}
      </Button>
      {editing && children}
    </Space>
  );
}
