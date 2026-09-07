import { Alert, Descriptions, Modal, Spin, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { mailboxApi } from '../../api/mailbox';
import MessageBody from '../mailbox/MessageBody';

/**
 * The email the reading came out of, for checking it by eye.
 *
 * <b>The whole review screen is an argument about what an email said</b>, and the one thing
 * that settles it is the email. Without this the reviewer is asked to accept or reject a
 * deadweight on the model's word, with the source three tabs away and no way to be sure they
 * are looking at the same message.
 *
 * <b>Opening it does not mark it read.</b> That is the point of `markRead: false` here and it
 * is not a detail: the mailbox's unread count means "somebody is waiting for you", and
 * checking a parse is not answering a broker. Reading it here would quietly clear a flag that
 * belongs to a different job.
 *
 * Fetched only while open — a long Outlook chain is a hundred kilobytes, and every row of the
 * queue has one behind it.
 */
export default function OriginalEmail({
  mailMessageId,
  open,
  onClose,
}: {
  mailMessageId?: number;
  open: boolean;
  onClose: () => void;
}) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['intake', 'original-email', mailMessageId],
    // markRead false: see above.
    queryFn: () => mailboxApi.get(mailMessageId!, false),
    enabled: open && mailMessageId != null,
  });

  const m = data?.message;

  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      width={900}
      title={m?.subject || 'The original email'}
      destroyOnClose
    >
      {isLoading && <Spin />}

      {isError && (
        <Alert
          type="warning"
          showIcon
          message="That message is no longer in the mailbox"
          description="The sync mirrors a server whose folders get cleaned out, so an email can go while everything read out of it stays. What the model answered is still on the parse record."
        />
      )}

      {m && (
        <>
          <Descriptions size="small" column={1} style={{ marginBottom: 12 }}>
            <Descriptions.Item label="From">
              {m.fromName ? `${m.fromName} <${m.fromAddress}>` : m.fromAddress}
            </Descriptions.Item>
            <Descriptions.Item label="Received">
              {m.receivedAt ? dayjs(m.receivedAt).format('D MMM YYYY HH:mm') : '—'}
            </Descriptions.Item>
            {data?.attachmentNames && (
              <Descriptions.Item label="Attachments">
                {data.attachmentNames}
                <br />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Attachments are not synced and were not read — a position list that arrived
                  as a spreadsheet is not in what the model saw.
                </Typography.Text>
              </Descriptions.Item>
            )}
          </Descriptions>

          <MessageBody html={data?.bodyHtml} text={data?.bodyText} height="55vh" />

          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Reading this here does not mark it read in the mailbox.
          </Typography.Text>
        </>
      )}
    </Modal>
  );
}
