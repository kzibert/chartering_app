import { useEffect, useState } from 'react';
import { Alert, Descriptions, Modal, Segmented, Space, Spin, Tag, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { mailboxApi } from '../api/mailbox';
import MessageBody from '../pages/mailbox/MessageBody';

/**
 * One email a record came out of, reduced to what a picker needs.
 *
 * <b>Neutral on purpose.</b> Two features hand this list over and they store provenance in
 * different tables — a review item's arrivals in `intake_item_sources`, a cargo's in
 * `cargo_sources` — with different columns and different names for the sender. Teaching this
 * component either shape would mean teaching it both, so each caller maps into this instead.
 */
export interface EmailSource {
  /** Null where the mailbox no longer holds the message: it can be named but not opened. */
  mailMessageId?: number;
  /** Who sent it, as the picker should label it. */
  label?: string;
  /** When it arrived, for telling two lists from the same broker apart. */
  when?: string;
  /** Whether the record's figures came from this arrival. */
  current?: boolean;
}

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
 *
 * <b>An item can have several.</b> One question about a hull is asked by however many emails
 * mention her, and they merge into one review item rather than one row each — so this takes the
 * whole list and puts a picker above the body. Settling a disagreement between two brokers is
 * exactly reading what each of them actually wrote, and that was impossible while each arrival
 * sat on a queue row of its own. One source shows no picker: there is nothing to choose.
 */
export default function OriginalEmail({
  mailMessageId,
  sources,
  open,
  onClose,
}: {
  /** The item's own message — what to show when there is no source list (a list row). */
  mailMessageId?: number;
  /** Every arrival behind the record, newest first. */
  sources?: EmailSource[];
  open: boolean;
  onClose: () => void;
}) {
  // Only the ones the mailbox still holds can be opened; the rest are named but not readable,
  // which is what ON DELETE SET NULL on the source row means in practice.
  const readable = (sources ?? []).filter((s) => s.mailMessageId != null);
  const choices: EmailSource[] = readable.length > 0
    ? readable
    : mailMessageId != null
      ? [{ mailMessageId, current: true }]
      : [];

  const [chosen, setChosen] = useState<number | undefined>(choices[0]?.mailMessageId);

  // Back to the newest whenever the modal is opened on a different item — a selection left
  // over from the last hull would show somebody else's email under this one's heading.
  useEffect(() => {
    if (open) setChosen(choices[0]?.mailMessageId);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, choices[0]?.mailMessageId, choices.length]);

  const showing = chosen ?? choices[0]?.mailMessageId;

  const { data, isLoading, isError } = useQuery({
    queryKey: ['intake', 'original-email', showing],
    // markRead false: see above.
    queryFn: () => mailboxApi.get(showing!, false),
    enabled: open && showing != null,
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
      {choices.length > 1 && (
        <Space direction="vertical" size={4} style={{ marginBottom: 12, width: '100%' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {choices.length} emails. The figures on the record are from the newest.
          </Typography.Text>
          <Segmented
            value={showing}
            onChange={(v) => setChosen(v as number)}
            options={choices.map((s) => ({
              value: s.mailMessageId!,
              label: (
                <Space size={4}>
                  {s.label ?? 'Unknown sender'}
                  {s.when && (
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      {dayjs(s.when).format('D MMM')}
                    </Typography.Text>
                  )}
                  {s.current && <Tag color="blue" style={{ marginInlineEnd: 0 }}>newest</Tag>}
                </Space>
              ),
            }))}
          />
        </Space>
      )}

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
