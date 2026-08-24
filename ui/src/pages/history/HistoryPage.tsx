import { Col, DatePicker, Form, Input, Row, Select, Space, Tooltip, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import {
  useChangeEntityTypes,
  useChangeUsers,
  useDataChanges,
  useDataChangeMutations,
} from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import ResponsiveTable from '../../components/ResponsiveTable';
import FilterPanel, { countActiveFilters } from '../../components/FilterPanel';
import { usePersistedFilters } from '../../components/usePersistedState';
import ChangeSummary, { ChangeTarget } from '../../components/ChangeSummary';
import { RevertButton } from '../../components/RecordHistory';
import type { DataChangeResponse, DataChangeFilter } from '../../api/dataChanges';

const OPERATIONS = [
  { value: '', label: 'Any change' },
  { value: 'create', label: 'Created' },
  { value: 'update', label: 'Changed' },
  { value: 'delete', label: 'Deleted' },
];

/**
 * Everything that has changed, newest first.
 *
 * The counterpart to the History tab in a record's drawer: that one answers "what happened
 * to this", and this one answers "what happened at all" — which is the question you have
 * when you know something is wrong but not yet what. Hence the free-text box searching the
 * values rather than only the record names: a deleted contact cannot be found by its
 * company any more, but it can still be found by the address it used to hold.
 */
export default function HistoryPage() {
  const [form] = Form.useForm();
  const [filters, setFilters] = usePersistedFilters<Partial<DataChangeFilter>>('history', form);
  const tc = useTableControls({ size: 25 }, 'history');

  const query = useDataChanges({ ...filters, page: tc.state.page, size: tc.state.size });
  const { data: entityTypes } = useChangeEntityTypes();
  const { data: users } = useChangeUsers();
  const { revert } = useDataChangeMutations();

  const rows = query.data?.content ?? [];

  const applyFilters = (values: Record<string, unknown>) => {
    // The range picker hands back dayjs objects; the API wants two ISO strings, and the
    // range itself is not a field the server knows about.
    const { range, ...rest } = values as { range?: [dayjs.Dayjs, dayjs.Dayjs] };
    setFilters({
      ...(rest as Partial<DataChangeFilter>),
      from: range?.[0]?.startOf('day').toISOString(),
      until: range?.[1]?.endOf('day').toISOString(),
    });
    tc.resetPage();
  };

  const columns: ColumnsType<DataChangeResponse> = [
    {
      title: 'When',
      key: 'when',
      width: 150,
      render: (_, c) => (
        <Tooltip title={dayjs(c.changedAt).format('D MMM YYYY HH:mm:ss')}>
          <Typography.Text style={{ fontSize: 13 }}>
            {dayjs(c.changedAt).format('D MMM HH:mm')}
          </Typography.Text>
        </Tooltip>
      ),
    },
    {
      title: 'Record',
      key: 'record',
      width: 260,
      render: (_, c) => <ChangeTarget change={c} />,
    },
    {
      title: 'Change',
      key: 'change',
      render: (_, c) => <ChangeSummary change={c} />,
    },
    {
      title: 'Who',
      key: 'who',
      width: 150,
      render: (_, c) => (
        <Space direction="vertical" size={0}>
          <Typography.Text style={{ fontSize: 13 }}>{c.changedBy ?? '—'}</Typography.Text>
          {c.context && (
            <Typography.Text type="secondary" italic style={{ fontSize: 11 }}>
              {c.context}
            </Typography.Text>
          )}
        </Space>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 60,
      render: (_, c) => (
        <RevertButton
          change={c}
          pending={revert.isPending}
          onRevert={() => revert.mutate(c.id)}
        />
      ),
    },
  ];

  return (
    <>
      {/* The Form wraps the panel: on a phone these fields render into a drawer portalled
          to the end of <body>, and only a Form above them in the tree still reaches them. */}
      <Form form={form} layout="vertical" onFinish={applyFilters}>
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
            <Col xs={12} md={5}>
              <Form.Item name="entityType" label="Record type">
                <Select
                  allowClear
                  placeholder="All types"
                  options={(entityTypes ?? []).map((t) => ({ value: t, label: t }))}
                />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="operation" label="Change" initialValue="">
                <Select options={OPERATIONS} />
              </Form.Item>
            </Col>
            <Col xs={12} md={5}>
              <Form.Item
                name="text"
                label="Contains"
                tooltip="Searches the record's name and both the old and new values — so a deleted record can be found by an address it used to hold."
              >
                <Input allowClear placeholder="e.g. @fednav.com" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="field" label="Field" tooltip="The exact property name, as shown in the Change column">
                <Input allowClear placeholder="e.g. working" />
              </Form.Item>
            </Col>
            <Col xs={12} md={3}>
              <Form.Item name="changedBy" label="Who">
                <Select
                  allowClear
                  placeholder="Anyone"
                  options={(users ?? []).map((u) => ({ value: u, label: u }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={7}>
              <Form.Item name="range" label="Between">
                <DatePicker.RangePicker style={{ width: '100%' }} allowEmpty={[true, true]} />
              </Form.Item>
            </Col>
          </Row>
        </FilterPanel>
      </Form>

      <ResponsiveTable<DataChangeResponse>
        rowKey={(c) => c.id}
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={rows}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
        mobile={{
          title: (c) => <ChangeTarget change={c} />,
          subtitle: (c) =>
            `${dayjs(c.changedAt).format('D MMM YYYY HH:mm')}${c.changedBy ? ` · ${c.changedBy}` : ''}`,
          fields: (c) => [
            { label: 'Change', value: <ChangeSummary change={c} /> },
            c.context && { label: 'Why', value: c.context },
          ],
          actions: (c) => (
            <RevertButton
              change={c}
              pending={revert.isPending}
              onRevert={() => revert.mutate(c.id)}
            />
          ),
        }}
      />
    </>
  );
}
