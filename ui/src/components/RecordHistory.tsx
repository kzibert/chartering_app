import { Alert, Button, Empty, List, Popconfirm, Space, Spin, Tooltip, Typography } from 'antd';
import { UndoOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useDataChanges, useDataChangeMutations } from '../api/hooks';
import ChangeSummary from './ChangeSummary';
import type { DataChangeResponse } from '../api/dataChanges';

/**
 * One record's history, for a drawer tab.
 *
 * Deliberately not the whole history page in miniature: there are no filters, because the
 * record is the filter, and the question being asked here is always "what has happened to
 * *this*". It shows the most recent page and links nowhere — a record with more history than
 * fits is rare, and the history page can be filtered to it when it happens.
 */
export default function RecordHistory({
  entityType,
  entityId,
  /**
   * Said above the list when this record has children whose history lives elsewhere — a
   * person's addresses, a company's people. Worth spelling out: an empty history on a
   * record whose contacts were edited yesterday otherwise reads as the log not working.
   */
  note,
}: {
  entityType: string;
  entityId?: number;
  note?: string;
}) {
  const enabled = entityId != null;
  const query = useDataChanges({ entityType, entityId, size: 50 }, enabled);

  if (!enabled) return null;
  if (query.isLoading) {
    return (
      <div style={{ padding: 24, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  const rows = query.data?.content ?? [];
  if (rows.length === 0) {
    return (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description="Nothing has changed on this record since the change log was switched on."
      />
    );
  }

  return (
    <>
      {note && <Alert type="info" showIcon style={{ marginBottom: 12 }} message={note} />}
      <ChangeList rows={rows} />
    </>
  );
}

/** The list itself, shared with the history page's mobile layout. */
export function ChangeList({ rows }: { rows: DataChangeResponse[] }) {
  const { revert } = useDataChangeMutations();

  return (
    <List
      size="small"
      dataSource={rows}
      renderItem={(change) => (
        <List.Item
          key={change.id}
          actions={[<RevertButton key="revert" change={change} pending={revert.isPending}
            onRevert={() => revert.mutate(change.id)} />]}
        >
          <Space direction="vertical" size={2} style={{ width: '100%' }}>
            <ChangeSummary change={change} />
            <Space size={6} wrap>
              <Tooltip title={dayjs(change.changedAt).format('D MMM YYYY HH:mm:ss')}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {dayjs(change.changedAt).format('D MMM YYYY HH:mm')}
                </Typography.Text>
              </Tooltip>
              {change.changedBy && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  by {change.changedBy}
                </Typography.Text>
              )}
              {change.context && (
                <Typography.Text type="secondary" italic style={{ fontSize: 12 }}>
                  {change.context}
                </Typography.Text>
              )}
            </Space>
          </Space>
        </List.Item>
      )}
    />
  );
}

/**
 * Put one field back.
 *
 * Disabled entries keep the button and explain themselves in its tooltip rather than hiding
 * it. "Why can I undo this row and not that one" is a fair question, and a button that
 * vanishes never answers it.
 */
export function RevertButton({
  change,
  pending,
  onRevert,
}: {
  change: { revertible: boolean; revertBlockedReason?: string; fieldName?: string; oldValue?: string };
  pending: boolean;
  onRevert: () => void;
}) {
  if (!change.revertible) {
    return (
      <Tooltip title={change.revertBlockedReason}>
        <Button size="small" type="text" icon={<UndoOutlined />} disabled />
      </Tooltip>
    );
  }
  return (
    <Popconfirm
      title="Put this value back?"
      description={
        <span style={{ maxWidth: 280, display: 'inline-block' }}>
          {change.fieldName} goes back to{' '}
          {change.oldValue == null ? <i>empty</i> : <b>{change.oldValue}</b>}. This counts as an
          edit of its own and will appear in the history.
        </span>
      }
      okText="Put it back"
      onConfirm={onRevert}
    >
      <Tooltip title="Undo this change">
        <Button size="small" type="text" icon={<UndoOutlined />} loading={pending} />
      </Tooltip>
    </Popconfirm>
  );
}
