import { Col, DatePicker, Form, Input, Row, Select, Space, Tag, Typography } from 'antd';
import { LinkOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import ResponsiveTable from '../../components/ResponsiveTable';
import FilterPanel, { countActiveFilters } from '../../components/FilterPanel';
import { usePersistedFilters } from '../../components/usePersistedState';
import { useTableControls } from '../../components/useTableControls';
import type { FeedItem, FeedItemFilter } from '../../api/feed';
import { useFeedItems, useFeedSources } from '../../feed/store';

const when = (i: FeedItem) => dayjs(i.publishedAt ?? i.fetchedAt).format('D MMM YYYY HH:mm');

const body = (i: FeedItem) => (
  <Space direction="vertical" size={0} style={{ width: '100%' }}>
    {i.title && (
      <Typography.Text strong>
        {i.title}{' '}
        {i.url && (
          <a href={i.url} target="_blank" rel="noreferrer">
            <LinkOutlined />
          </a>
        )}
      </Typography.Text>
    )}
    <Typography.Paragraph
      ellipsis={{ rows: 3, expandable: true, symbol: 'more' }}
      style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
    >
      {i.text}
    </Typography.Paragraph>
  </Space>
);

/** Everything collected, newest first — what the summaries were written from, as the sources wrote it. */
export default function ItemsTab() {
  const [form] = Form.useForm();
  const [filters, setFilters] = usePersistedFilters<Partial<FeedItemFilter>>('feed.items', form);
  const tc = useTableControls({ size: 25 }, 'feed.items');
  const sources = useFeedSources();
  const query = useFeedItems({ ...filters, page: tc.state.page, size: tc.state.size });

  const apply = (values: Record<string, unknown>) => {
    const { range, ...rest } = values as { range?: [dayjs.Dayjs, dayjs.Dayjs] };
    setFilters({
      ...(rest as Partial<FeedItemFilter>),
      from: range?.[0]?.format('YYYY-MM-DD'),
      to: range?.[1]?.format('YYYY-MM-DD'),
    });
    tc.resetPage();
  };

  const columns: ColumnsType<FeedItem> = [
    { title: 'When', key: 'when', width: 150, render: (_, i) => when(i) },
    { title: 'Source', key: 'source', width: 180, render: (_, i) => <Tag>{i.sourceName}</Tag> },
    { title: 'Item', key: 'item', render: (_, i) => body(i) },
  ];

  return (
    <>
      <Form form={form} layout="vertical" onFinish={apply}>
        <FilterPanel
          form={form}
          activeCount={countActiveFilters(filters)}
          onReset={() => {
            form.resetFields();
            setFilters({});
            tc.resetPage();
          }}
        >
          <Row gutter={12}>
            <Col xs={24} md={6}>
              <Form.Item name="sourceId" label="Source">
                <Select
                  allowClear
                  placeholder="All sources"
                  options={(sources.data ?? []).map((s) => ({ value: s.id, label: s.name }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={10}>
              <Form.Item name="q" label="Contains">
                <Input allowClear placeholder="e.g. Danube, $/t, handysize" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="range" label="Between">
                <DatePicker.RangePicker style={{ width: '100%' }} allowEmpty={[true, true]} />
              </Form.Item>
            </Col>
          </Row>
        </FilterPanel>
      </Form>

      <ResponsiveTable<FeedItem>
        rowKey={(i) => i.id}
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={query.data?.content ?? []}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
        mobile={{
          title: (i) => i.title ?? i.sourceName,
          subtitle: (i) => `${when(i)} · ${i.sourceName}`,
          fields: (i) => [{ label: 'Text', value: body({ ...i, title: undefined }) }],
        }}
      />
    </>
  );
}
