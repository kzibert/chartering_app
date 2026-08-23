import { useEffect, useState, type CSSProperties, type ReactNode } from 'react';
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
  extra,
  children,
  style,
}: {
  editing: boolean;
  onToggle: (on: boolean) => void;
  /**
   * Controls shown in both modes, sharing the toolbar's row. For things that only change
   * how the list is displayed — expand all, collapse all — which are as useful while
   * browsing as while editing and would look stranded on a row of their own.
   */
  extra?: ReactNode;
  /** Controls shown only while editing — typically the "Add …" button(s). */
  children?: ReactNode;
  /**
   * Overrides the row's own spacing. For the drawers, which sit this toggle *inside* the
   * status strip it governs rather than on a row above a list — the bottom margin that
   * separates a toolbar from its list only knocks it out of line there.
   */
  style?: CSSProperties;
}) {
  return (
    <Space style={{ marginBottom: 8, ...style }} wrap>
      <Button
        size="small"
        type={editing ? 'primary' : 'default'}
        icon={<EditOutlined />}
        onClick={() => onToggle(!editing)}
      >
        {editing ? 'Done' : 'Edit'}
      </Button>
      {editing && children}
      {extra}
    </Space>
  );
}
