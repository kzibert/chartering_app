import type { ReactNode } from 'react';
import { Button, Divider, Popconfirm, Space, Typography } from 'antd';
import { DeleteOutlined } from '@ant-design/icons';
import ConfirmTag from './ConfirmTag';
import BanButton from './BanButton';
import type { ConfirmRequest } from '../api/types';

interface Props {
  /** The word for this thing, lowercase — "vessel", "company". Used in the copy. */
  entity: string;
  /** What is being acted on, for the delete confirmation. */
  name?: string;

  /**
   * Confirm and ban, shown only for a record that has those flags. A person has neither —
   * confirming is about an address being real, and the ban list is kept per company — so
   * their form passes the delete alone and gets a section with only that in it.
   */
  confirmed?: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  confirmLoading?: boolean;
  onConfirm?: (body: ConfirmRequest) => void;
  onUnconfirm?: () => void;

  banned?: boolean;
  banLoading?: boolean;
  onToggleBan?: (banned: boolean) => void;

  /**
   * Immediate actions particular to this record, shown before Delete — the person form's
   * "has left the company" toggle is the one of these. They obey the same rule as the rest
   * of the section: they fire on click, not on Save.
   */
  children?: ReactNode;

  deleteLoading?: boolean;
  onDelete: () => void;
  /** What else goes when this record does — shown in the delete confirmation. */
  deleteWarning?: ReactNode;
}

/**
 * The immediate actions on one record, as a section at the foot of its edit form.
 *
 * Which of them appear depends on what the record has: a vessel or a company brings its
 * confirm flag and its ban; a person has neither, and brings a "left the company" toggle of
 * their own instead. Delete is the only one every record has.
 *
 * These used to sit in the drawer header and the list's status column, where they were
 * one click from a record you were only reading — and delete had no confirmation at all.
 * They live here now because the edit form is the one place you arrive at by saying you
 * intend to change this thing.
 *
 * **They are not part of Save, and the note on screen says so.** Each fires its own
 * endpoint the moment it is pressed: confirm and ban both return the updated record, and
 * delete is gone the instant it is confirmed. Cancel closes the form, it does not put any
 * of them back. That is worth the two lines of screen space it costs — the alternative,
 * folding them into the form's own submit, would make Ban a checkbox whose effect you
 * could not tell apart from a typo in the notes field.
 */
export default function RecordActions({
  entity,
  name,
  children,
  confirmed,
  confirmedAt,
  confirmedBy,
  confirmLoading,
  onConfirm,
  onUnconfirm,
  banned,
  banLoading,
  onToggleBan,
  deleteLoading,
  onDelete,
  deleteWarning,
}: Props) {
  return (
    <>
      <Divider orientation="left" plain style={{ marginTop: 8 }}>
        This {entity}
      </Divider>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
        These take effect straight away — they are not saved with the form, and Cancel does
        not undo them.
      </Typography.Paragraph>
      <Space wrap>
        {/* `editing` is unconditional here: this section only exists inside the edit form,
            which is the deliberate act the flag was hidden behind in the first place. */}
        {onConfirm && onUnconfirm && (
          <ConfirmTag
            editing
            confirmed={confirmed ?? false}
            confirmedAt={confirmedAt}
            confirmedBy={confirmedBy}
            loading={confirmLoading}
            onConfirm={onConfirm}
            onUnconfirm={onUnconfirm}
          />
        )}
        {onToggleBan && (
          <BanButton banned={banned ?? false} loading={banLoading} onToggle={onToggleBan} />
        )}
        {children}
        {/* A confirmation the drawer's Delete never had: it fired on the first click, and
            the record was gone before the pointer left the button. */}
        <Popconfirm
          title={`Delete ${name ? `"${name}"` : `this ${entity}`}?`}
          description={deleteWarning}
          okText="Delete"
          okButtonProps={{ danger: true }}
          onConfirm={onDelete}
        >
          <Button danger icon={<DeleteOutlined />} loading={deleteLoading}>
            Delete
          </Button>
        </Popconfirm>
      </Space>
    </>
  );
}
