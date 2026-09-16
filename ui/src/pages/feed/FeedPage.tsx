import { useEffect, useRef } from 'react';
import { Alert, App, Button, Card, Space, Tabs, Tag, Typography } from 'antd';
import { CloudDownloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useFeedInvalidator, useFeedMutations, useFeedStatus } from '../../feed/store';
import { usePersistedState } from '../../components/usePersistedState';
import SummariesTab from './SummariesTab';
import TopicsTab from './TopicsTab';
import SourcesTab from './SourcesTab';
import ItemsTab from './ItemsTab';

/**
 * The Feed: what outside sources are saying, summarised per topic by the local model.
 *
 * <p>The same page on every deployment. The hosted instance shows the summaries the office wrote
 * and edits sources, topics and the prompt; Fetch now and Summarise appear only where
 * `analysisEnabled` says this instance can do them, rather than as buttons that 404.
 */
export default function FeedPage() {
  const { message: toast } = App.useApp();
  const status = useFeedStatus();
  const invalidate = useFeedInvalidator();
  const { fetchAll, runSummaries } = useFeedMutations();
  const [tab, setTab] = usePersistedState<string>('feed.tab', 'summaries');
  const s = status.data;
  const analysis = s?.analysisEnabled === true;

  // A fetch or a run ending changes what every tab shows; the status poll is what notices.
  const wasBusy = useRef(false);
  useEffect(() => {
    const busy = !!(s?.fetchRunning || s?.summaryRunning);
    if (wasBusy.current && !busy) invalidate();
    wasBusy.current = busy;
  }, [s?.fetchRunning, s?.summaryRunning, invalidate]);

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card
        title={
          <Space wrap>
            Feed
            <Tag>{s?.items ?? 0} items</Tag>
            <Tag>
              {s?.enabledSources ?? 0}/{s?.sources ?? 0} sources on
            </Tag>
            <Tag>
              {s?.selectedTopics ?? 0}/{s?.topics ?? 0} topics selected
            </Tag>
            {analysis && (
              <Tag color={s?.reachable ? 'green' : 'red'}>{s?.reachable ? 'Model up' : 'Model down'}</Tag>
            )}
          </Space>
        }
        extra={
          analysis && (
            <Space wrap>
              <Button
                icon={<CloudDownloadOutlined />}
                loading={s?.fetchRunning || fetchAll.isPending}
                disabled={s?.fetchRunning}
                onClick={() => fetchAll.mutate(undefined, { onSuccess: () => toast.info('Fetching sources…') })}
              >
                {s?.fetchRunning ? 'Fetching…' : 'Fetch now'}
              </Button>
              <Button
                type="primary"
                icon={<ThunderboltOutlined />}
                loading={s?.summaryRunning || runSummaries.isPending}
                disabled={s?.summaryRunning || !s?.reachable || (s?.selectedTopics ?? 0) === 0}
                onClick={() => runSummaries.mutate(undefined, { onSuccess: () => toast.info('Summarising…') })}
              >
                {s?.summaryRunning ? 'Summarising…' : `Summarise ${s?.selectedTopics ?? 0} topic(s)`}
              </Button>
            </Space>
          )
        }
      >
        <Space direction="vertical" size={4} style={{ width: '100%' }}>
          {s?.summaryRunning && (
            <Alert
              type="info"
              showIcon
              message={`Topic ${s.runTopicIndex ?? '?'} of ${s.runTopicCount ?? '?'}: ${s.runTopicName ?? ''}`}
              description={s.runStage}
            />
          )}
          {(s?.warnings ?? []).map((w) => (
            <Alert key={w} type="warning" showIcon message={w} />
          ))}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {analysis ? (
              <>
                {s?.lastFetchMessage ? `Last fetch: ${s.lastFetchMessage}` : 'Not fetched since the app started.'}
                {s?.nextFetchAt ? ` Next about ${dayjs(s.nextFetchAt).format('HH:mm')}.` : ' The fetch timer is off.'}
                {s?.lastRunMessage ? ` Last summary run: ${s.lastRunMessage}` : ''}
              </>
            ) : (
              'Summaries are written by the office instance, where the model runs. Sources, topics and the prompt can be edited here.'
            )}
          </Typography.Text>
        </Space>
      </Card>

      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'summaries', label: 'Summaries', children: <SummariesTab /> },
          { key: 'topics', label: 'Topics & prompt', children: <TopicsTab /> },
          { key: 'sources', label: 'Sources', children: <SourcesTab analysis={analysis} /> },
          { key: 'items', label: 'Items', children: <ItemsTab /> },
        ]}
      />
    </Space>
  );
}
