import { useState } from 'react';
import { ReloadOutlined } from '@ant-design/icons';
import { App, Button, List, Space, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import type { QueueMessageOutcome, QueueOutcome } from '../../api/intake';
import { useIntakeMutations } from '../../intake/store';

/** On the waiting list, whether this pick added it, found it there already, or re-queued it. */
const ON_LIST: ReadonlySet<QueueOutcome> = new Set(['QUEUED', 'REQUEUED', 'ALREADY_QUEUED', 'REREAD']);

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
  REREAD: { color: 'green', label: 'Read again' },
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
    case 'REREAD':
      return 'Queued to be read again; the new reading replaces the old one.';
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
 *
 * This renders inside modal.info, so it keeps its own state and calls the mutation itself. The
 * modal's content sits in the antd App holder, which is inside QueryClientProvider (main.tsx), so
 * the hooks reach the same client as the Mailbox does.
 */
export default function QueueResultList({ entries }: { entries: QueueMessageOutcome[] }) {
  const { message } = App.useApp();
  const { queueMessages } = useIntakeMutations();
  // The order is fixed at the first render. A row re-read in place keeps its place rather than
  // jumping to the foot of the list under the pointer.
  const [rows, setRows] = useState<QueueMessageOutcome[]>(() => [
    ...entries.filter((e) => !ON_LIST.has(e.outcome)),
    ...entries.filter((e) => ON_LIST.has(e.outcome)),
  ]);
  // The ids with a read-again request in flight: their buttons show loading and the rest wait.
  const [pending, setPending] = useState<number[]>([]);

  const alreadyRead = rows.filter((e) => e.outcome === 'ALREADY_READ').map((e) => e.mailMessageId);
  const readAllBusy = alreadyRead.length > 0 && alreadyRead.every((id) => pending.includes(id));

  const rereadIds = async (ids: number[]) => {
    setPending((p) => [...p, ...ids]);
    try {
      const { messages } = await queueMessages.mutateAsync({ mailMessageIds: ids, reread: true });
      // Each row takes the server's answer for it; a row the answer does not mention is left alone.
      const returned = new Map(messages.map((m) => [m.mailMessageId, m]));
      setRows((prev) => prev.map((e) => returned.get(e.mailMessageId) ?? e));
    } catch (e: unknown) {
      const detail = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
      message.error(detail || 'These messages could not be queued to be read again.');
    } finally {
      setPending((p) => p.filter((id) => !ids.includes(id)));
    }
  };

  return (
    <>
      {alreadyRead.length >= 2 && (
        <div style={{ marginBottom: 8 }}>
          <Button
            size="small"
            icon={<ReloadOutlined />}
            loading={readAllBusy}
            disabled={pending.length > 0}
            onClick={() => rereadIds(alreadyRead)}
          >
            Read all again ({alreadyRead.length})
          </Button>
        </div>
      )}
      <List
        size="small"
        dataSource={rows}
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
                {e.outcome === 'ALREADY_READ' && (
                  <div style={{ marginTop: 4 }}>
                    <Button
                      size="small"
                      icon={<ReloadOutlined />}
                      loading={pending.includes(e.mailMessageId)}
                      disabled={pending.length > 0 && !pending.includes(e.mailMessageId)}
                      onClick={() => rereadIds([e.mailMessageId])}
                    >
                      Read again
                    </Button>
                  </div>
                )}
              </div>
            </List.Item>
          );
        }}
      />
    </>
  );
}
