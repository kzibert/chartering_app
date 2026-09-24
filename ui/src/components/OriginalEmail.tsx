import { useEffect, useState } from 'react';
import { Alert, Descriptions, Modal, Segmented, Space, Spin, Tag, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { mailboxApi } from '../api/mailbox';
import { feedApi } from '../api/feed';
import MessageBody from '../pages/mailbox/MessageBody';

/**
 * One email a record came out of, reduced to what a picker needs.
 *
 * <b>Neutral on purpose.</b> Several features hand this list over and they store provenance in
 * different tables — a review item's arrivals in `intake_item_sources`, a cargo's in
 * `cargo_sources` — with different columns and different names for the sender. Teaching this
 * component any one shape would mean teaching it all of them, so each caller maps into this.
 *
 * <b>An arrival is a message or a post.</b> A circular read off ship.gr's open boards is the
 * same document as a mailed one and is checked the same way, so it belongs behind the same
 * button rather than a second one somewhere else. Exactly one of the two ids is set.
 */
export interface EmailSource {
  /** Null where the mailbox no longer holds the message: it can be named but not opened. */
  mailMessageId?: number;
  /** The board post, for an arrival that came off a page rather than out of the mailbox. */
  feedItemId?: number;
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
  open,
  onClose,
  ...rest
}: OriginalEmailViewProps & {
  open: boolean;
  onClose: () => void;
}) {
  return (
    <Modal
      open={open}
      onCancel={onClose}
      footer={null}
      width={900}
      title={rest.feedItemId != null && rest.mailMessageId == null ? 'The original post' : 'The original email'}
      destroyOnClose
    >
      <OriginalEmailView {...rest} active={open} />
    </Modal>
  );
}

interface OriginalEmailViewProps {
  /** The item's own message — what to show when there is no source list (a list row). */
  mailMessageId?: number;
  /** The item's own post, for the same case where the arrival came off a board. */
  feedItemId?: number;
  /** Every arrival behind the record, newest first. */
  sources?: EmailSource[];
  /** Which of `sources` to open on, when the reader picked one from a list; else the newest. */
  initialMailMessageId?: number;
  /** How tall the body may grow before it scrolls. */
  height?: string;
}

/**
 * The same thing without the modal, for a screen that keeps the email beside what was read
 * out of it — the cargo's full tonnage list does, because a ship offered against a figure
 * the broker never wrote is the mistake that screen exists to prevent. The subject is a row
 * of its own here rather than the modal's title, so the two read identically.
 */
export function OriginalEmailView({
  mailMessageId,
  feedItemId,
  sources,
  initialMailMessageId,
  height = '55vh',
  active = true,
}: OriginalEmailViewProps & {
  /** Fetch only while shown; the modal passes its open flag. */
  active?: boolean;
}) {
  const open = active;
  // Only the ones still held can be opened; the rest are named but not readable, which is
  // what ON DELETE SET NULL on the source row means in practice — a mailbox folder emptied, a
  // board whose source was removed.
  const readable = (sources ?? []).filter((s) => s.mailMessageId != null || s.feedItemId != null);
  const choices: EmailSource[] = readable.length > 0
    ? readable
    : mailMessageId != null
      ? [{ mailMessageId, current: true }]
      : feedItemId != null
        ? [{ feedItemId, current: true }]
        : [];

  // A key over the pair, because the two ids are sequences of their own and message 12 and
  // post 12 are different things. Nothing but this component ever sees it.
  const keyOf = (s: EmailSource) => (s.feedItemId != null ? `post:${s.feedItemId}` : `mail:${s.mailMessageId}`);
  const wanted = initialMailMessageId != null ? `mail:${initialMailMessageId}` : undefined;
  const opening = choices.some((c) => keyOf(c) === wanted) ? wanted : (choices[0] && keyOf(choices[0]));
  const [chosen, setChosen] = useState<string | undefined>(opening);

  // Back to the newest (or the one asked for) whenever the modal is opened on a different
  // item — a selection left over from the last hull would show somebody else's email under
  // this one's heading.
  useEffect(() => {
    if (open) setChosen(opening);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, opening, choices.length]);

  const showingKey = chosen ?? (choices[0] && keyOf(choices[0]));
  const showing = choices.find((c) => keyOf(c) === showingKey);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['intake', 'original-email', showingKey],
    // markRead false: see above.
    queryFn: () => mailboxApi.get(showing!.mailMessageId!, false),
    enabled: open && showing?.mailMessageId != null,
  });

  const post = useQuery({
    queryKey: ['intake', 'original-post', showingKey],
    queryFn: () => feedApi.item(showing!.feedItemId!),
    enabled: open && showing?.feedItemId != null,
  });

  const m = data?.message;
  const p = post.data;

  if (choices.length === 0) {
    return <Typography.Text type="secondary">No email or post behind this record.</Typography.Text>;
  }

  return (
    <>
      {choices.length > 1 && (
        <Space direction="vertical" size={4} style={{ marginBottom: 12, width: '100%' }}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {choices.length} arrivals. The figures on the record are from the newest.
          </Typography.Text>
          <Segmented
            value={showingKey}
            onChange={(v) => setChosen(v as string)}
            options={choices.map((s) => ({
              value: keyOf(s),
              label: (
                <Space size={4}>
                  {s.label ?? (s.feedItemId != null ? 'A board' : 'Unknown sender')}
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

      {(isLoading || post.isLoading) && <Spin />}

      {isError && (
        <Alert
          type="warning"
          showIcon
          message="That message is no longer in the mailbox"
          description="The sync mirrors a server whose folders get cleaned out, so an email can go while everything read out of it stays. What the model answered is still on the parse record."
        />
      )}

      {post.isError && (
        <Alert
          type="warning"
          showIcon
          message="That post is no longer stored"
          description="Posts go when their source is removed. What was read out of this one stays, and the model's answer is still on the parse record."
        />
      )}

      {p && (
        <>
          <Descriptions size="small" column={1} style={{ marginBottom: 12 }}>
            {p.title && <Descriptions.Item label="Title">{p.title}</Descriptions.Item>}
            <Descriptions.Item label="From">
              {p.sourceName}
              {p.url && (
                <>
                  {' · '}
                  <Typography.Link href={p.url} target="_blank" rel="noreferrer">
                    open the page
                  </Typography.Link>
                </>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Posted">
              {p.publishedAt ? dayjs(p.publishedAt).format('D MMM YYYY') : '—'}
            </Descriptions.Item>
          </Descriptions>

          <MessageBody text={p.text} height={height} />

          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            A copy taken when the board was read. Boards roll their entries off the bottom, so
            the page may no longer show this one.
          </Typography.Text>
        </>
      )}

      {m && (
        <>
          <Descriptions size="small" column={1} style={{ marginBottom: 12 }}>
            {m.subject && <Descriptions.Item label="Subject">{m.subject}</Descriptions.Item>}
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

          <MessageBody html={data?.bodyHtml} text={data?.bodyText} height={height} />

          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Reading this here does not mark it read in the mailbox.
          </Typography.Text>
        </>
      )}
    </>
  );
}
