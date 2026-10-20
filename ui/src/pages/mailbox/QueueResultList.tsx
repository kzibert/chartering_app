import { List, Space, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import type { QueueMessageOutcome, QueueOutcome } from '../../api/intake';

/** On the waiting list, whether this pick added it or found it there already. */
const ON_LIST: ReadonlySet<QueueOutcome> = new Set(['QUEUED', 'REQUEUED', 'ALREADY_QUEUED']);

/**
 * Whether a waiting-list answer needs the list shown at all. The short success line is enough
 * when every picked email went on the list or was already on it; anything else (already read,
 * no text, not found) has to be named one by one, because "1 skipped" does not say which.
 */
export function queueNeedsReport(entries: QueueMessageOutcome[]): boolean {
  return entries.some((e) => !ON_LIST.has(e.outcome));
}

const TAG: Record<QueueOutcome, { color?: string; label: string }> = {
  QUEUED: { color: 'green', label: 'Added' },
  REQUEUED: { color: 'green', label: 'Added again' },
  ALREADY_QUEUED: { color: 'blue', label: 'Already waiting' },
  ALREADY_READ: { label: 'Already read' },
  NO_TEXT: { color: 'orange', label: 'No text' },
  NOT_FOUND: { color: 'red', label: 'Not found' },
};

/** One line of reason per outcome; a read time is shown the way the Mailbox shows its dates. */
function reasonFor(e: QueueMessageOutcome): string {
  switch (e.outcome) {
    case 'QUEUED':
      return 'Waiting for the next sweep to read it.';
    case 'REQUEUED':
      return 'It had failed or been left out before.';
    case 'ALREADY_QUEUED':
      return 'Already on the waiting list.';
    case 'ALREADY_READ': {
      const when = e.parsedAt ? `Read on ${dayjs(e.parsedAt).format('D MMM YYYY HH:mm')}` : 'Read earlier';
      const result = e.parseStatus === 'SKIPPED' ? 'nothing to read' : 'parsed';
      return `${when} (${result}). Its results are on the Intake tab's Log.`;
    }
    case 'NO_TEXT':
      return 'This email has no text body, so there is nothing for the model to read.';
    case 'NOT_FOUND':
      return 'Not found on your desk.';
  }
}

/**
 * The emails a waiting-list pick did not add, first, then the ones it did. Rows wrap rather than
 * truncate: on a phone the modal is the full width of the screen and a subject is the only thing
 * that tells two forwarded copies apart.
 */
export default function QueueResultList({ entries }: { entries: QueueMessageOutcome[] }) {
  const notAdded = entries.filter((e) => !ON_LIST.has(e.outcome));
  const added = entries.filter((e) => ON_LIST.has(e.outcome));
  return (
    <List
      size="small"
      dataSource={[...notAdded, ...added]}
      rowKey={(e) => `${e.mailMessageId}`}
      renderItem={(e) => {
        const tag = TAG[e.outcome];
        return (
          <List.Item>
            <div style={{ minWidth: 0, width: '100%' }}>
              <Space wrap size={4} style={{ maxWidth: '100%' }}>
                <Tag color={tag.color}>{tag.label}</Tag>
                <Typography.Text strong style={{ wordBreak: 'break-word' }}>
                  {e.subject || '(no subject)'}
                </Typography.Text>
              </Space>
              {e.fromAddress && (
                <div>
                  <Typography.Text type="secondary" style={{ fontSize: 12, wordBreak: 'break-all' }}>
                    {e.fromAddress}
                  </Typography.Text>
                </div>
              )}
              <div>
                <Typography.Text type="secondary">{reasonFor(e)}</Typography.Text>
              </div>
            </div>
          </List.Item>
        );
      }}
    />
  );
}
