import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Drawer,
  Empty,
  List,
  Pagination,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { LinkOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { FeedSummary } from '../../api/feed';
import { useFeedSummaries, useFeedSummary, useFeedTopics } from '../../feed/store';

const PAGE_SIZE = 10;

const statusColor = { RUNNING: 'processing', DONE: 'green', FAILED: 'red' } as const;

/**
 * Every summary, newest first, one card each.
 *
 * <p>The figures under the title are part of the answer, not diagnostics: a summary written from
 * twelve items of forty says something different from one written from all forty, and nothing in
 * its prose would show it. So the counts, the approach and anything dropped are on the card.
 */
export default function SummariesTab() {
  const [topicId, setTopicId] = useState<number>();
  const [page, setPage] = useState(0);
  const [openId, setOpenId] = useState<number>();
  const topics = useFeedTopics();
  const query = useFeedSummaries({ topicId, page, size: PAGE_SIZE });
  const rows = query.data?.content ?? [];

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Select
        allowClear
        placeholder="All topics"
        style={{ width: '100%', maxWidth: 360 }}
        value={topicId}
        onChange={(v) => {
          setTopicId(v);
          setPage(0);
        }}
        options={(topics.data ?? []).map((t) => ({ value: t.id, label: t.name }))}
      />

      {query.isLoading ? (
        <Spin />
      ) : rows.length === 0 ? (
        <Empty description="No summaries yet. Add sources and topics, fetch, then Summarise." />
      ) : (
        rows.map((s) => <SummaryCard key={s.id} summary={s} onOpen={() => setOpenId(s.id)} />)
      )}

      {(query.data?.totalElements ?? 0) > PAGE_SIZE && (
        <Pagination
          current={page + 1}
          pageSize={PAGE_SIZE}
          total={query.data?.totalElements}
          onChange={(p) => setPage(p - 1)}
          showSizeChanger={false}
        />
      )}

      <SummaryDrawer id={openId} onClose={() => setOpenId(undefined)} />
    </Space>
  );
}

function SummaryCard({ summary: s, onOpen }: { summary: FeedSummary; onOpen: () => void }) {
  return (
    <Card
      size="small"
      title={
        <Space wrap size={4}>
          <Typography.Text strong>{s.topicName}</Typography.Text>
          <Tag color={statusColor[s.status]}>{s.status.toLowerCase()}</Tag>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {dayjs(s.createdAt).format('D MMM YYYY HH:mm')}
          </Typography.Text>
        </Space>
      }
      extra={
        s.status !== 'RUNNING' && (
          <Button size="small" onClick={onOpen}>
            Sources used
          </Button>
        )
      }
    >
      <Space wrap size={4} style={{ marginBottom: 8 }}>
        {s.strategy && (
          <Tooltip
            title={
              s.strategy === 'SINGLE_PASS'
                ? 'Everything fitted one request.'
                : `Read in batches, notes condensed ${s.levels ?? 0} time(s), then summarised.`
            }
          >
            <Tag color={s.strategy === 'SINGLE_PASS' ? 'blue' : 'purple'}>
              {s.strategy === 'SINGLE_PASS' ? 'one pass' : 'map-reduce'}
            </Tag>
          </Tooltip>
        )}
        {s.itemsUsed != null && (
          <Tag>
            {s.itemsUsed} item(s) read of {s.itemsConsidered ?? '?'} collected
          </Tag>
        )}
        {(s.itemsDropped ?? 0) > 0 && <Tag color="orange">{s.itemsDropped} dropped at the call cap</Tag>}
        {s.llmCalls != null && <Tag>{s.llmCalls} model call(s)</Tag>}
        {s.durationMs != null && <Tag>{Math.round(s.durationMs / 1000)} s</Tag>}
        {s.periodFrom && (
          <Tag>
            {dayjs(s.periodFrom).format('D MMM')} – {dayjs(s.periodTo).format('D MMM')}
          </Tag>
        )}
      </Space>
      {s.status === 'FAILED' && <Alert type="error" showIcon message={s.error ?? 'Failed'} />}
      {s.status === 'RUNNING' && <Spin size="small" />}
      {s.content && (
        <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{s.content}</Typography.Paragraph>
      )}
    </Card>
  );
}

function SummaryDrawer({ id, onClose }: { id?: number; onClose: () => void }) {
  const detail = useFeedSummary(id);
  const s = detail.data;

  return (
    <Drawer open={id != null} onClose={onClose} width={720} title={s ? `${s.topicName} — sources used` : 'Sources used'}>
      {detail.isLoading || !s ? (
        <Spin />
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Text type="secondary">
            {s.model ? `Model ${s.model} · ` : ''}
            {s.contextWindow ? `${s.contextWindow.toLocaleString()}-token window · ` : ''}
            {(s.promptTokens ?? 0).toLocaleString()} tokens in, {(s.completionTokens ?? 0).toLocaleString()} out
          </Typography.Text>
          <Collapse
            items={[
              {
                key: 'prompt',
                label: 'The prompt this was written under',
                children: (
                  <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: 12 }}>
                    {s.systemPrompt ?? '—'}
                  </Typography.Paragraph>
                ),
              },
            ]}
          />
          <List
            dataSource={s.items ?? []}
            locale={{ emptyText: 'No items — nothing matched this topic, or the items were removed with their source.' }}
            renderItem={(i) => (
              <List.Item>
                <Space direction="vertical" size={2} style={{ width: '100%' }}>
                  <Space wrap size={4}>
                    <Tag>{i.sourceName}</Tag>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {dayjs(i.publishedAt ?? i.fetchedAt).format('D MMM YYYY HH:mm')}
                    </Typography.Text>
                    {i.title && <Typography.Text strong>{i.title}</Typography.Text>}
                    {i.url && (
                      <a href={i.url} target="_blank" rel="noreferrer">
                        <LinkOutlined />
                      </a>
                    )}
                  </Space>
                  <Typography.Paragraph
                    ellipsis={{ rows: 4, expandable: true, symbol: 'more' }}
                    style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
                  >
                    {i.text}
                  </Typography.Paragraph>
                </Space>
              </List.Item>
            )}
          />
        </Space>
      )}
    </Drawer>
  );
}
