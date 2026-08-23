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

  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  confirmLoading?: boolean;
  onConfirm: (body: ConfirmRequest) => void;
  onUnconfirm: () => void;

  banned: boolean;
  banLoading?: boolean;
  onToggleBan: (banned: boolean) => void;

  deleteLoading?: boolean;
  onDelete: () => void;
  /** What else goes when this record does — shown in the delete confirmation. */
  deleteWarning?: ReactNode;
}

/**
 * Confirm, ban and delete for one record, as a section of its edit form.
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
        <ConfirmTag
          editing
          confirmed={confirmed}
          confirmedAt={confirmedAt}
          confirmedBy={confirmedBy}
          loading={confirmLoading}
          onConfirm={onConfirm}
          onUnconfirm={onUnconfirm}
        />
        <BanButton banned={banned} loading={banLoading} onToggle={onToggleBan} />
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
