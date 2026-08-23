import { useState } from 'react';
import { Button, Space, Tag, Tooltip } from 'antd';
import { CheckCircleTwoTone, ClockCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import ConfirmModal from './ConfirmModal';
import type { ConfirmRequest } from '../api/types';

interface Props {
  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  loading?: boolean;
  /**
   * Reveals the confirm/unconfirm control. Off by default, and the default is the point:
   * a caller that forgets to pass it gets a read-only tag, which is the harmless failure.
   * The other default would put a one-click write next to every row it was forgotten on.
   */
  editing?: boolean;
  onConfirm: (body: ConfirmRequest) => void;
  onUnconfirm: () => void;
}

/**
 * Status tag for any entity with a confirm block, plus the confirm/unconfirm control
 * behind an edit mode.
 *
 * **Why the control hides.** Confirming is an attestation — somebody checked this record
 * against the world on a date and put their name to it — and it sat as a bare link inside
 * a status strip, one stray click from being rewritten. Unconfirm was worse: no modal, no
 * confirmation, and the click threw away the who and the when with no way to recover them
 * short of asking the person who had done it. The tag is information and always shows; the
 * write is an edit and waits to be asked for, the same way every other write on a contact
 * row does (see `ContactLine`).
 */
export default function ConfirmTag({
  confirmed,
  confirmedAt,
  confirmedBy,
  loading,
  editing = false,
  onConfirm,
  onUnconfirm,
}: Props) {
  const [open, setOpen] = useState(false);

  return (
    <Space size="small">
      {confirmed ? (
        <Tooltip
          title={`${confirmedBy ?? 'unknown'}${
            confirmedAt ? ' • ' + dayjs(confirmedAt).format('YYYY-MM-DD HH:mm') : ''
          }`}
        >
          <Tag icon={<CheckCircleTwoTone twoToneColor="#52c41a" />} color="success">
            Confirmed
          </Tag>
        </Tooltip>
      ) : (
        <Tag icon={<ClockCircleOutlined />} color="warning">
          Needs confirm
        </Tag>
      )}
      {editing &&
        (confirmed ? (
          // Unconfirming discards confirmedBy, confirmedAt and the notes — the record of
          // who vouched for this and when — and the server keeps no history of them. Edit
          // mode gets it off a hair trigger; the tooltip says what is actually lost.
          <Tooltip title="Clear the confirmation, along with who confirmed it and when">
            <Button size="small" type="link" loading={loading} onClick={onUnconfirm}>
              unconfirm
            </Button>
          </Tooltip>
        ) : (
          <Button size="small" type="link" loading={loading} onClick={() => setOpen(true)}>
            confirm
          </Button>
        ))}
      <ConfirmModal
        open={open}
        loading={loading}
        onCancel={() => setOpen(false)}
        onSubmit={(body) => {
          setOpen(false);
          onConfirm(body);
        }}
      />
    </Space>
  );
}
