import { Alert, Button, Collapse, Descriptions, Progress, Space, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import { useAskIndex, useStartAskIndex } from '../../ask/store';

const count = (n: number) => n.toLocaleString('en-GB');

/**
 * How much of the desk's text is searchable, and a button to make more of it so.
 *
 * Collapsed by default: a broker asking a question wants the answer, and the index is a thing
 * they look at when an answer is thin. The header still says how many sources are waiting, so a
 * stale index is visible without opening it.
 *
 * Indexing is the desk's own: the counts and the run are per desk, and another desk's run shows
 * as idle here, the same as on the other embedding-backed screens.
 */
export default function AskIndexCard() {
  const index = useAskIndex(true);
  const start = useStartAskIndex();
  const data = index.data;
  const running = data?.running === true;

  const summary = data
    ? `${count(data.indexedSources)} indexed · ${count(data.pendingSources)} waiting`
    : 'Index';

  return (
    <Collapse
      ghost
      style={{ background: '#fff', borderRadius: 8, border: '1px solid rgba(5,5,5,0.06)' }}
      items={[
        {
          key: 'index',
          label: (
            <Space size={8} wrap>
              <Typography.Text strong>Search index</Typography.Text>
              <Typography.Text type="secondary">{summary}</Typography.Text>
              {running && <Tag color="processing">indexing</Tag>}
            </Space>
          ),
          children: (
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              {index.isError && (
                <Alert type="error" showIcon message="The index status could not be read." />
              )}

              {data && (
                <Descriptions size="small" column={1}>
                  <Descriptions.Item label="Model">{data.model ?? '—'}</Descriptions.Item>
                  <Descriptions.Item label="Indexed">{count(data.indexedSources)} sources</Descriptions.Item>
                  <Descriptions.Item label="Waiting">
                    {count(data.pendingSources)} new or changed
                  </Descriptions.Item>
                  <Descriptions.Item label="Last run">
                    {data.lastFinishedAt ? dayjs(data.lastFinishedAt).format('D MMM YYYY HH:mm') : 'Never'}
                  </Descriptions.Item>
                </Descriptions>
              )}

              {data && running && (
                <Progress
                  size="small"
                  status="active"
                  percent={data.total > 0 ? Math.round((data.done / data.total) * 100) : 0}
                  format={() => `${count(data.done)} of ${count(data.total)}`}
                />
              )}

              {data?.lastError && !running && (
                <Alert
                  type="warning"
                  showIcon
                  message="The last run stopped early"
                  description={`${data.lastError} What was indexed before that is kept, so running it again picks up where it stopped.`}
                />
              )}

              <Button
                type="primary"
                disabled={running || start.isPending || !data}
                loading={start.isPending}
                onClick={() => start.mutate()}
              >
                Index now
              </Button>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                Reads the desk's mail, board posts, cargoes and positions, and embeds only what is new
                or has changed since the last run.
              </Typography.Text>
            </Space>
          ),
        },
      ]}
    />
  );
}
