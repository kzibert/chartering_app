import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Descriptions,
  Empty,
  Modal,
  Popconfirm,
  Space,
  Statistic,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  EyeOutlined,
  PlusOutlined,
  PlayCircleOutlined,
  RedoOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ColumnsType } from 'antd/es/table';
import { campaignsApi } from '../../api/campaigns';
import { circulationsApi, circulationListsApi } from '../../api/circulations';
import { useCurrentList, listKeys } from '../../circulations/store';
import { reportAdd } from '../../circulations/addResult';
import type { CirculationRecipientStatus, CirculationRunRecipient } from '../../api/types';

/** Sent, failed and "never reached" have to be distinguishable at a glance. */
const STATUS: Record<CirculationRecipientStatus, { colour: string; label: string; hint: string }> = {
  SENT: { colour: 'success', label: 'sent', hint: 'Accepted by the mail server' },
  FAILED: { colour: 'error', label: 'failed', hint: 'Refused, or out of retries' },
  PENDING: {
    colour: 'default',
    label: 'not reached',
    hint: 'Queued but never sent — the run ended before getting here',
  },
  SKIPPED_DUPLICATE: {
    colour: 'warning',
    label: 'duplicate',
    hint: 'The same address appeared earlier in the list, so it was mailed once',
  },
  SKIPPED_NOT_WORKING: {
    colour: 'warning',
    label: 'not working',
    hint: 'Flagged as a dead address when the run started, so it was never mailed',
  },
  SKIPPED_NOT_FOR_CIRC: {
    colour: 'warning',
    label: 'not for circ',
    hint: 'Flagged never to be circulated to — the address works, it is just kept out of bulk mail',
  },
};

function stamp(iso?: string) {
  return iso ? iso.replace('T', ' ').slice(0, 19) : '—';
}

/**
 * One past circulation: the circular that was composed, every address it touched, and —
 * on demand — the exact message any one of them received.
 */
export default function HistoryModal({
  runId,
  onClose,
}: {
  runId: number | undefined;
  onClose: () => void;
}) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const current = useCurrentList();
  const [messageFor, setMessageFor] = useState<CirculationRunRecipient | null>(null);
  const [pickedIds, setPickedIds] = useState<number[]>([]);
  // Rows left after the Outcome column's filter. Null until the table reports one, which
  // is what makes "all" mean "all of them" on an unfiltered table.
  const [filtered, setFiltered] = useState<CirculationRunRecipient[] | null>(null);

  const detailQ = useQuery({
    queryKey: ['circulation', runId],
    queryFn: () => circulationsApi.get(runId!),
    enabled: runId != null,
  });

  // A different run is a different set of rows; carrying ticks or a filter across would
  // act on people the user never saw.
  useEffect(() => {
    setPickedIds([]);
    setFiltered(null);
  }, [runId]);

  const messageQ = useQuery({
    queryKey: ['circulation', runId, 'message', messageFor?.id],
    queryFn: () => circulationsApi.message(runId!, messageFor!.id),
    enabled: runId != null && messageFor != null,
  });

  // Whether anything is in flight right now — the API runs one campaign at a time, so
  // offering Resume while another send is going would only produce a 409.
  const statusQ = useQuery({ queryKey: ['campaign', 'status'], queryFn: campaignsApi.status });
  const busy = statusQ.data?.running ?? false;

  const afterLaunch = (note: string) => {
    message.success(note);
    qc.invalidateQueries({ queryKey: ['campaign'] });
    qc.invalidateQueries({ queryKey: ['circulations'] });
    qc.invalidateQueries({ queryKey: ['circulation', runId] });
    onClose();
  };

  const resumeMut = useMutation({
    mutationFn: () => campaignsApi.resume(runId!),
    onSuccess: (s) =>
      afterLaunch(`Resumed — ${Math.max(0, s.total - s.sent - s.failed)} left to send`),
  });

  const restartMut = useMutation({
    mutationFn: () => campaignsApi.restart(runId!),
    onSuccess: () => afterLaunch('Restarted — sending the whole circulation again'),
  });

  const detail = detailQ.data;
  const run = detail?.run;
  const rows = detail?.recipients ?? [];
  const shown = filtered ?? rows;

  /**
   * What "copy to the current list" acts on: the ticked rows, or everything the Outcome
   * filter currently leaves on screen. Filtering to *failed* and copying is the natural
   * way to build a chase list, so "all" has to mean what is visible, not the whole run.
   */
  const picked = pickedIds.length ? rows.filter((r) => pickedIds.includes(r.id)) : shown;
  const scopeLabel = pickedIds.length ? `${pickedIds.length} selected` : `all ${shown.length}`;

  const copyToCurrent = useMutation({
    mutationFn: () =>
      circulationListsApi.addEntries(
        current.listId!,
        picked.map((r) => ({
          email: r.email,
          contactId: r.contactId,
          personId: r.personId,
          personName: r.personName,
          greetingName: r.greetingName,
          title: r.title,
          companyId: r.companyId,
          companyName: r.companyName,
        })),
      ),
    onSuccess: (r) => {
      reportAdd(message, r, 'the current list');
      qc.invalidateQueries({ queryKey: listKeys.all });
    },
  });

  const columns: ColumnsType<CirculationRunRecipient> = [
    { title: 'Email', dataIndex: 'email', width: 260 },
    {
      title: 'Name',
      key: 'name',
      width: 200,
      render: (_, r) =>
        r.personName ? (
          <Space size={4}>
            {r.title && <Typography.Text type="secondary">{r.title}</Typography.Text>}
            {r.personName}
          </Space>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: 'Greeting used',
      dataIndex: 'greetingName',
      width: 150,
      // The merged {{greeting}} falls back the same way the sender did, so this column
      // shows what the recipient actually read, not what the contact record now says.
      render: (v: string | undefined, r) => v ?? r.personName ?? 'Sirs',
    },
    { title: 'Company', dataIndex: 'companyName', width: 200 },
    {
      title: 'Outcome',
      key: 'status',
      width: 130,
      filters: Object.entries(STATUS).map(([value, s]) => ({ text: s.label, value })),
      onFilter: (value, r) => r.status === value,
      render: (_, r) => (
        <Tooltip title={r.error ?? STATUS[r.status].hint}>
          <Tag color={STATUS[r.status].colour}>
            {STATUS[r.status].label}
            {r.attempts > 1 ? ` (${r.attempts} tries)` : ''}
          </Tag>
        </Tooltip>
      ),
    },
    { title: 'Sent at', dataIndex: 'sentAt', width: 170, render: (v: string) => stamp(v) },
    {
      title: '',
      key: 'message',
      width: 48,
      render: (_, r) =>
        // Only an address that was actually mailed has a message to show.
        r.status === 'SENT' || r.status === 'FAILED' ? (
          <Tooltip title="Show the message this recipient received">
            <Button
              type="text"
              size="small"
              icon={<EyeOutlined />}
              aria-label="Show this recipient's message"
              onClick={() => setMessageFor(r)}
            />
          </Tooltip>
        ) : null,
    },
  ];

  return (
    <>
      <Modal
        open={runId != null}
        onCancel={onClose}
        footer={null}
        width={1000}
        title={run ? `Circulation — ${run.subject}` : 'Circulation'}
      >
        {detailQ.isLoading || !detail || !run ? (
          <Empty description="Loading…" />
        ) : (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }} bordered>
              <Descriptions.Item label="Started">{stamp(run.startedAt)}</Descriptions.Item>
              <Descriptions.Item label="Finished">{stamp(run.finishedAt)}</Descriptions.Item>
              <Descriptions.Item label="Outcome">
                <Tag>{run.state.replace(/_/g, ' ')}</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="From">
                {detail.fromName} &lt;{detail.fromAddress}&gt;
              </Descriptions.Item>
              {detail.replyTo && (
                <Descriptions.Item label="Reply-To">{detail.replyTo}</Descriptions.Item>
              )}
              <Descriptions.Item label="List">{run.listName ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="Footer">{run.footerName ?? 'none'}</Descriptions.Item>
            </Descriptions>

            <Space size="large" wrap>
              <Statistic title="Sent" value={run.sent} suffix={`/ ${run.total}`} />
              <Statistic
                title="Failed"
                value={run.failed}
                valueStyle={run.failed ? { color: '#cf1322' } : undefined}
              />
              <Statistic title="Skipped" value={run.skipped} />
            </Space>

            {/* Send it again: carry the same run on to whoever it never reached, or repeat
                the whole thing as a circulation of its own. */}
            <Space wrap>
              {run.resumable && (
                <Popconfirm
                  title="Carry on from where it stopped?"
                  description={
                    <div style={{ maxWidth: 320 }}>
                      Sends only to the {run.pending} this run never reached. Nobody already
                      mailed is mailed twice, and the outcome is recorded against this same
                      circulation.
                    </div>
                  }
                  okText="Resume"
                  onConfirm={() => resumeMut.mutate()}
                  disabled={busy}
                >
                  <Button
                    type="primary"
                    icon={<PlayCircleOutlined />}
                    loading={resumeMut.isPending}
                    disabled={busy}
                  >
                    Resume ({run.pending} not reached)
                  </Button>
                </Popconfirm>
              )}
              <Popconfirm
                title="Send this circulation again, from the top?"
                description={
                  <div style={{ maxWidth: 340 }}>
                    Everyone here is mailed again, including the {run.sent} who already received
                    it. Recorded as a new circulation — this one is left as it is. Addresses
                    flagged not-working since are dropped.
                  </div>
                }
                okText="Restart"
                onConfirm={() => restartMut.mutate()}
                disabled={busy}
              >
                <Button icon={<RedoOutlined />} loading={restartMut.isPending} disabled={busy}>
                  Restart
                </Button>
              </Popconfirm>
              {busy && (
                <Typography.Text type="secondary">
                  Another campaign is sending — one runs at a time.
                </Typography.Text>
              )}
            </Space>

            {run.message && (
              <Alert
                type={run.state === 'COMPLETED' ? 'success' : run.state === 'ABORTED' ? 'error' : 'info'}
                showIcon
                message={run.message}
                description={detail.lastError ? `Last error: ${detail.lastError}` : undefined}
              />
            )}

            <Tabs
              items={[
                {
                  key: 'recipients',
                  label: `Recipients (${rows.length})`,
                  children: (
                    <Space direction="vertical" size="small" style={{ width: '100%' }}>
                      <Tooltip
                        title={
                          'Copy these people onto the current circulation list, ready to send to ' +
                          'again. History is not changed. Filter by outcome first to chase only ' +
                          'the ones that failed or were never reached.'
                        }
                      >
                        <Button
                          type="primary"
                          ghost
                          icon={<PlusOutlined />}
                          loading={copyToCurrent.isPending}
                          disabled={picked.length === 0 || current.listId == null}
                          onClick={() => copyToCurrent.mutate()}
                        >
                          Add {scopeLabel} to current list
                        </Button>
                      </Tooltip>
                      <Table<CirculationRunRecipient>
                        rowKey="id"
                        size="small"
                        columns={columns}
                        dataSource={rows}
                        pagination={{ pageSize: 25, showSizeChanger: false }}
                        scroll={{ x: true }}
                        rowSelection={{
                          selectedRowKeys: pickedIds,
                          // Ticks on other pages leave dataSource; without this, paging
                          // away would silently shrink the selection.
                          preserveSelectedRowKeys: true,
                          onChange: (keys) => setPickedIds(keys as number[]),
                        }}
                        // currentDataSource is the post-filter rows, which is what the
                        // unticked "all" case copies.
                        onChange={(_p, _f, _s, extra) => setFiltered(extra.currentDataSource)}
                      />
                    </Space>
                  ),
                },
                {
                  key: 'circular',
                  label: 'The circular',
                  children: (
                    <>
                      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                        Subject: {run.subject}
                      </Typography.Paragraph>
                      <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                        Shown before the mail merge — the placeholders below were filled in per
                        recipient. Open a recipient to see their copy.
                      </Typography.Paragraph>
                      <div
                        style={{ border: '1px solid #f0f0f0', borderRadius: 4, padding: 16 }}
                        dangerouslySetInnerHTML={{ __html: detail.composedHtml }}
                      />
                    </>
                  ),
                },
              ]}
            />
          </Space>
        )}
      </Modal>

      <Modal
        open={messageFor != null}
        onCancel={() => setMessageFor(null)}
        footer={null}
        width={760}
        title={messageFor ? `Message sent to ${messageFor.email}` : 'Message'}
      >
        {messageQ.isLoading || !messageQ.data ? (
          <Empty description="Loading…" />
        ) : (
          <Tabs
            items={[
              {
                key: 'html',
                label: 'HTML',
                children: (
                  <>
                    <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                      Subject: {messageQ.data.subject}
                    </Typography.Paragraph>
                    <div
                      style={{ border: '1px solid #f0f0f0', borderRadius: 4, padding: 16 }}
                      dangerouslySetInnerHTML={{ __html: messageQ.data.html }}
                    />
                  </>
                ),
              },
              {
                key: 'text',
                label: 'Plain text',
                children: (
                  <>
                    <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
                      The alternative part that went out alongside the HTML — this is what a
                      plain-text client displayed.
                    </Typography.Paragraph>
                    <pre style={{ whiteSpace: 'pre-wrap', fontSize: 12, margin: 0 }}>
                      {messageQ.data.text}
                    </pre>
                  </>
                ),
              },
            ]}
          />
        )}
      </Modal>
    </>
  );
}
