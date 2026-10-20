import { useState, type Key } from 'react';
import { Button, Drawer, Popconfirm, Segmented, Space, Typography, message } from 'antd';
import { ThunderboltOutlined, StopOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { AxiosError } from 'axios';
import dayjs from 'dayjs';
import ResponsiveTable from '../../components/ResponsiveTable';
import { useIntakeMutations, useWaiting } from '../../intake/store';
import type { WaitingArrivalResponse } from '../../api/intake';

interface Props {
  open: boolean;
  onClose: () => void;
  /** Counts for the two segments, from the same status the header reads. */
  mailCount: number;
  webCount: number;
  /** A read is going on. Read now is off while it runs, rather than being refused. */
  running: boolean;
  /** The model server answers. Without it a read can only fail, so the button is off. */
  modelUp: boolean;
}

const PAGE_SIZE = 25;
/** The server's own limits: one read is at most 200 mail ids, one leave-out list 500. */
const READ_MAX = 200;
const LEAVE_OUT_MAX = 500;

/**
 * The list behind "Waiting to be read", for taking things out of it before the model is asked.
 *
 * Why this exists: the header number is what the next sweep will read, which is right until a
 * broker's newsletter or a board full of stale postings is in that number. The two actions are
 * the two ways to change the answer: leave an arrival out (it is filed as Ignored, so Reopen on
 * the log puts it back) or read a picked mail now rather than waiting for the sweep. Nothing
 * here deletes anything; the log stays the record of what was decided.
 *
 * Selection outlives the page. Ticks are kept by id across pages, so a long list can be gone
 * through and acted on once. Switching source clears them: a mail id and a post id can be the
 * same number, and an action aimed at the wrong one would be silent.
 */
export default function WaitingDrawer({ open, onClose, mailCount, webCount, running, modelUp }: Props) {
  const [source, setSource] = useState<'MAIL' | 'WEB'>('MAIL');
  const [page, setPage] = useState(0);
  const [picked, setPicked] = useState<number[]>([]);
  const { leaveOut, parseMessages } = useIntakeMutations();

  const query = useWaiting({ source, page, size: PAGE_SIZE }, open);
  const rows = query.data?.content ?? [];
  const total = query.data?.totalElements ?? 0;
  const isMail = source === 'MAIL';

  const switchSource = (next: 'MAIL' | 'WEB') => {
    setSource(next);
    setPage(0);
    setPicked([]);
  };

  const close = () => {
    setPicked([]);
    onClose();
  };

  const columns: ColumnsType<WaitingArrivalResponse> = [
    {
      title: 'Received',
      key: 'receivedAt',
      width: 110,
      render: (_, r) => (r.receivedAt ? dayjs(r.receivedAt).format('D MMM HH:mm') : '—'),
    },
    isMail
      ? {
          title: 'From',
          key: 'from',
          width: 200,
          render: (_, r) => (
            <div style={{ minWidth: 0 }}>
              <Typography.Text ellipsis style={{ display: 'block' }}>
                {r.fromName || r.fromAddress || '—'}
              </Typography.Text>
              {r.companyName && (
                <Typography.Text type="secondary" ellipsis style={{ display: 'block', fontSize: 12 }}>
                  {r.companyName}
                </Typography.Text>
              )}
            </div>
          ),
        }
      : {
          title: 'Board',
          key: 'board',
          width: 160,
          render: (_, r) => r.boardName || '—',
        },
    {
      title: 'Subject',
      key: 'subject',
      render: (_, r) => (
        <div style={{ minWidth: 0 }}>
          <Typography.Text ellipsis style={{ display: 'block' }}>
            {r.subject || '(no subject)'}
          </Typography.Text>
          {r.preview && (
            <Typography.Text
              type="secondary"
              ellipsis={{ tooltip: r.preview }}
              style={{ display: 'block', fontSize: 12 }}
            >
              {r.preview}
            </Typography.Text>
          )}
        </div>
      ),
    },
  ];

  // What the selection means on the server: mail ids for MAIL, post ids for WEB.
  const onLeaveOut = () =>
    leaveOut.mutate(isMail ? { mailMessageIds: picked } : { feedItemIds: picked }, {
      onSuccess: (res) => {
        message.success(`${res.leftOut} left out of the queue`);
        setPicked([]);
      },
    });

  // Read now is quiet at the client, because a 409 needs its own words, so its failures are
  // said here. Everything else the interceptor already reports.
  const onRead = () =>
    parseMessages.mutate(picked, {
      onSuccess: () => {
        message.success(`Reading ${picked.length} now. They appear on Needs review and What was read as they are done.`);
        setPicked([]);
      },
      onError: (e) => {
        const err = e as AxiosError<{ message?: string }>;
        message.error(
          err.response?.status === 409
            ? 'A read is already running. Try again when it finishes.'
            : (err.response?.data?.message ?? err.message ?? 'The read could not start.'),
        );
      },
    });

  const pickedCount = picked.length;
  const leaveOutTooMany = pickedCount > LEAVE_OUT_MAX;
  const readTooMany = pickedCount > READ_MAX;

  return (
    <Drawer open={open} width={820} onClose={close} title="Waiting to be read">
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Segmented
          block
          value={source}
          onChange={(v) => switchSource(v as 'MAIL' | 'WEB')}
          options={[
            { label: `Mail (${mailCount})`, value: 'MAIL' },
            { label: `Off the web (${webCount})`, value: 'WEB' },
          ]}
        />

        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          The sweep takes the boards first, then mail newest first, inside the lookback. Leaving
          something out files it as Ignored on What was read, where Reopen puts it back.
        </Typography.Text>

        <Space wrap>
          <Popconfirm
            title={`Leave out ${pickedCount} ${pickedCount === 1 ? 'arrival' : 'arrivals'}?`}
            description="They will not be read. Each can be reopened from What was read."
            okText="Leave out"
            okButtonProps={{ danger: true }}
            onConfirm={onLeaveOut}
          >
            <Button
              danger
              icon={<StopOutlined />}
              disabled={pickedCount === 0 || leaveOutTooMany}
              loading={leaveOut.isPending}
              title={leaveOutTooMany ? `At most ${LEAVE_OUT_MAX} at a time` : undefined}
            >
              Leave out ({pickedCount})
            </Button>
          </Popconfirm>

          {isMail && (
            <Button
              type="primary"
              icon={<ThunderboltOutlined />}
              onClick={onRead}
              disabled={pickedCount === 0 || readTooMany || running || !modelUp}
              loading={parseMessages.isPending}
              title={
                !modelUp
                  ? 'The model server is not answering'
                  : running
                    ? 'A read is already running'
                    : readTooMany
                      ? `At most ${READ_MAX} at a time`
                      : undefined
              }
            >
              Read now ({pickedCount})
            </Button>
          )}
        </Space>

        <ResponsiveTable<WaitingArrivalResponse>
          rowKey={(r) => r.id}
          size="small"
          tableLayout="fixed"
          loading={query.isLoading}
          columns={columns}
          dataSource={rows}
          rowSelection={{
            selectedRowKeys: picked as Key[],
            preserveSelectedRowKeys: true,
            onChange: (keys) => setPicked(keys as number[]),
          }}
          pagination={{
            current: page + 1,
            pageSize: PAGE_SIZE,
            total,
            showSizeChanger: false,
          }}
          onChange={(pag) => setPage((pag.current ?? 1) - 1)}
          locale={{
            emptyText: isMail
              ? 'Nothing waiting from the mailbox inside the lookback.'
              : 'Nothing waiting from the boards.',
          }}
          mobile={{
            title: (r) => r.subject || '(no subject)',
            subtitle: (r) => (isMail ? r.fromName || r.fromAddress || '—' : r.boardName || '—'),
            fields: (r) => [
              { label: 'Received', value: r.receivedAt ? dayjs(r.receivedAt).format('D MMM HH:mm') : '—' },
              isMail && r.companyName && { label: 'Company', value: r.companyName },
              r.preview && { label: 'Preview', value: r.preview },
            ],
          }}
        />
      </Space>
    </Drawer>
  );
}
