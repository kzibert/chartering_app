import { Alert, Button, Descriptions, Drawer, Space, Tag, Typography, message } from 'antd';
import dayjs from 'dayjs';
import { useIntakeMutations, useParsedEmail } from '../../intake/store';
import { EMAIL_TYPES, parseStatusMeta } from './labels';

interface Props {
  parsedId?: number;
  onClose: () => void;
}

/**
 * One parse, with the model's answer verbatim.
 *
 * <b>Why the raw answer is kept at all</b>, when the useful half is already filed as
 * positions and cargoes: it is the only thing that settles the question that actually comes
 * up — did the model read the email wrong, or did we file its answer wrong. Without it that
 * is unanswerable a week later, because the email says one thing and the row says another
 * and nothing shows the step in between.
 *
 * It is fetched only here and never in the list: a long position list is tens of kilobytes,
 * and a page of twenty would be a megabyte of JSON to draw a table of counts.
 */
export default function ParsedEmailDrawer({ parsedId, onClose }: Props) {
  const query = useParsedEmail(parsedId);
  const { reopen } = useIntakeMutations();
  const p = query.data;

  if (!parsedId) return null;

  const type = p?.emailType ? EMAIL_TYPES[p.emailType] : undefined;
  const meta = p ? parseStatusMeta(p.status) : undefined;

  return (
    <Drawer
      open
      width={720}
      onClose={onClose}
      title={p?.subject || 'Loading…'}
      loading={query.isLoading}
      extra={
        p && p.status === 'FAILED' && p.mailMessageId ? (
          <Button
            loading={reopen.isPending}
            onClick={() =>
              reopen.mutate(p.mailMessageId!, {
                onSuccess: () => {
                  message.success('Queued to be read again on the next sweep.');
                  onClose();
                },
              })
            }
          >
            Read again
          </Button>
        ) : undefined
      }
    >
      {p && (
        <>
          {p.error && (
            <Alert
              type={p.status === 'SKIPPED' ? 'info' : 'error'}
              showIcon
              style={{ marginBottom: 16 }}
              message={p.status === 'SKIPPED' ? 'Nothing to read' : 'The parse failed'}
              description={p.error}
            />
          )}

          <Descriptions size="small" column={2} bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label="From" span={2}>
              {p.fromName ? `${p.fromName} <${p.fromAddress}>` : p.fromAddress || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="Outcome">
              {meta && <Tag color={meta.colour}>{meta.label}</Tag>}
            </Descriptions.Item>
            <Descriptions.Item label="Read as">
              {type ? <Tag color={type.colour}>{type.label}</Tag> : p.emailType || '—'}
            </Descriptions.Item>
            <Descriptions.Item label="Filed" span={2}>
              <Space size={4} wrap>
                <Tag color="green">{p.positionsApplied} position(s)</Tag>
                <Tag color="blue">{p.cargoesApplied} cargo(es)</Tag>
                <Tag color="orange">{p.itemsRaised} to review</Tag>
              </Space>
            </Descriptions.Item>
            <Descriptions.Item label="Read at">
              {p.parsedAt ? dayjs(p.parsedAt).format('D MMM YYYY HH:mm') : '—'}
            </Descriptions.Item>
            <Descriptions.Item label="Took">
              {p.durationMs != null ? `${(p.durationMs / 1000).toFixed(1)}s` : '—'}
              {p.attempts > 1 ? ` · attempt ${p.attempts}` : ''}
            </Descriptions.Item>
            <Descriptions.Item label="Model" span={2}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {p.modelName || '—'}
                {p.promptChars != null ? ` · ${p.promptChars.toLocaleString()} chars sent` : ''}
              </Typography.Text>
            </Descriptions.Item>
          </Descriptions>

          {p.rawJson && (
            <>
              <Typography.Title level={5}>What the model answered</Typography.Title>
              <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                Verbatim. This is what the filed positions and cargoes were derived from, and
                the only way to tell a misreading from a mis-filing.
              </Typography.Paragraph>
              {/* Its own scroller: a long answer is wide as well as tall, and the page body
                  must never scroll sideways. */}
              <pre
                style={{
                  maxHeight: 420,
                  overflow: 'auto',
                  background: 'rgba(0,0,0,0.03)',
                  padding: 12,
                  borderRadius: 6,
                  fontSize: 12,
                  margin: 0,
                }}
              >
                {pretty(p.rawJson)}
              </pre>
            </>
          )}
        </>
      )}
    </Drawer>
  );
}

/**
 * Indented if it parses, as it came if it does not.
 *
 * The unparseable case is exactly the one worth looking at — it is why the row says FAILED —
 * so it has to render rather than throw.
 */
function pretty(raw: string) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}
