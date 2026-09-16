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
  /**
   * Reveals the confirm control alone, for a record that is not confirmed yet — never
   * unconfirm, whatever the record's state.
   *
   * It is a separate prop from `editing` rather than a widening of it because the two
   * are not the same permission. Confirming adds an attestation, and the modal collects a
   * name and a note on the way, so it cannot happen by accident and leaves more behind
   * than it found. Unconfirming destroys one — who vouched for this record and when, with
   * no history kept of either — and stays where it was, at the foot of the edit form.
   */
  confirmable?: boolean;
  /** Title for the confirm modal. Used with `confirmable`, where the record needs naming. */
  confirmTitle?: string;
  /** A line above the modal's fields saying what is being attested to. */
  confirmDescription?: string;
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
 *
 * **Why `confirmable` lets half of it back out.** Checking a record is something you do
 * while reading it — you have the record open, you have just rung the office — and sending
 * that through the edit form meant opening a form over the thing you were reading and
 * scrolling past every field to reach the foot of it. That is enough friction that records
 * stay unconfirmed. Only the adding half comes out; see the prop.
 */
export default function ConfirmTag({
  confirmed,
  confirmedAt,
  confirmedBy,
  loading,
  editing = false,
  confirmable = false,
  confirmTitle,
  confirmDescription,
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
      {/* The read-only screens' own button. Not `type="link"` like the one above: there it
          sits inside a form full of controls and a link is the quiet member of them, here
          it is the one write on a page of text and reads as nothing at all. */}
      {!editing && confirmable && !confirmed && (
        <Button size="small" loading={loading} onClick={() => setOpen(true)}>
          Confirm
        </Button>
      )}
      <ConfirmModal
        open={open}
        title={confirmTitle}
        description={confirmDescription}
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
