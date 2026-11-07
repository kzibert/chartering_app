import { Alert, Button, Card, Space, Switch, Table, Tag, Tooltip, Typography, message } from 'antd';
import { CloudDownloadOutlined, GlobalOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { ColumnsType } from 'antd/es/table';
import { intakeApi } from '../../api/intake';
import { feedApi, type FeedSource } from '../../api/feed';
import { useSession } from '../../auth/session';
import { roleAtLeast } from '../../api/auth';

/**
 * The open boards this queue reads.
 *
 * <b>The mailbox is not the only door.</b> ship.gr's Open Cargoes and Open Ships pages are
 * circulars the same firms paste by hand — "25,000 MT ±10% MOLCO Wheat, St Petersburg /
 * Durrës", signed with a full style — so they go through the same model as the mail and land
 * in the same places: positions on Open fleet, cargoes on Cargoes, everything else here.
 *
 * <b>Which boards this desk reads is chosen here.</b> It used to be a switch on the Feed tab,
 * and the Feed is switched off for now (FEED_ENABLED), so a desk administrator sees every board
 * the installation has with the switch beside it, and everybody else sees the ones being read.
 * Adding, renaming or re-pointing a board is still the Feed tab's, a platform administrator's,
 * for when the Feed is back: two forms editing one row would be two places to look when one of
 * them was wrong.
 *
 * <b>Fetching is not parsing.</b> This reads the pages and stores what is new; the sweep reads
 * what it stored. They are apart for the reason the mail sync and the sweep are — one is
 * seconds of somebody else's web server, the other is minutes of a GPU — so the count of posts
 * waiting goes up here and comes down when Parse now is pressed.
 */
export default function WebSources({ waiting }: { waiting: number }) {
  const qc = useQueryClient();
  const choosesIntake = roleAtLeast(useSession().role, 'TENANT_ADMIN');
  const sources = useQuery({
    queryKey: ['intake', 'sources'],
    queryFn: intakeApi.sources,
    staleTime: 30_000,
    enabled: !choosesIntake,
  });
  // A desk administrator chooses among every board, so they read the whole list; a board is a
  // website source with a parser of its own, never a Telegram channel or an RSS feed.
  const boards = useQuery({
    queryKey: ['intake', 'boards'],
    queryFn: () => feedApi.sources().then((all) => all.filter((s) => s.kind === 'WEBSITE')),
    staleTime: 30_000,
    enabled: choosesIntake,
  });
  const subscribe = useMutation({
    mutationFn: (v: { id: number; on: boolean }) => feedApi.setReadIntoIntake(v.id, v.on),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['intake'] }),
  });

  const fetchNow = useMutation({
    mutationFn: (sourceId?: number) => intakeApi.fetchSources(sourceId),
    onSuccess: () => {
      message.success('Reading the boards. What arrives is parsed on the next sweep.');
      // The fetch runs on a worker, so the counts move a moment after the call returns.
      // Re-asked rather than guessed: a board that answered 304 added nothing, and a card
      // claiming otherwise would be wrong in the one direction nobody would check.
      setTimeout(() => {
        qc.invalidateQueries({ queryKey: ['intake', 'sources'] });
        qc.invalidateQueries({ queryKey: ['intake', 'status'] });
      }, 4000);
    },
    onError: (e: Error) => message.error(e.message),
  });

  const rows = (choosesIntake ? boards.data : sources.data) ?? [];
  const loading = choosesIntake ? boards.isLoading : sources.isLoading;

  const columns: ColumnsType<FeedSource> = [
    ...(choosesIntake
      ? [{
          title: (
            <Tooltip title="Whether this desk sends the board's posts through the parser as circulars. Each desk chooses for itself.">
              Read
            </Tooltip>
          ),
          key: 'intake',
          width: 70,
          render: (_: unknown, s: FeedSource) => (
            <Switch
              size="small"
              checked={s.intoIntake}
              loading={subscribe.isPending}
              onChange={(on) => subscribe.mutate({ id: s.id, on })}
            />
          ),
        }]
      : []),
    {
      title: 'Board',
      key: 'name',
      render: (_, s) => (
        <div style={{ minWidth: 0 }}>
          <Typography.Text strong style={{ fontSize: 13 }}>
            {s.name}
          </Typography.Text>
          <div>
            <Typography.Link
              href={s.url}
              target="_blank"
              rel="noreferrer"
              style={{ fontSize: 11 }}
              onClick={(e) => e.stopPropagation()}
            >
              {s.url}
            </Typography.Link>
          </div>
        </div>
      ),
    },
    {
      title: 'Last read',
      key: 'lastFetchedAt',
      width: 200,
      render: (_, s) => (
        <Space size={4} wrap>
          {!s.enabled && (
            <Tooltip title="Switched off on the Feed tab. Everything it has already collected is kept — that is what switching off is for.">
              <Tag>off</Tag>
            </Tooltip>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {s.lastFetchedAt ? dayjs(s.lastFetchedAt).format('D MMM HH:mm') : 'never'}
          </Typography.Text>
          {s.lastNewItems != null && s.lastNewItems > 0 && (
            <Tag color="blue">+{s.lastNewItems}</Tag>
          )}
        </Space>
      ),
    },
    {
      title: 'Posts held',
      dataIndex: 'itemCount',
      key: 'itemCount',
      width: 110,
    },
    {
      title: '',
      key: 'actions',
      width: 110,
      render: (_, s) => (
        <Button
          size="small"
          icon={<CloudDownloadOutlined />}
          loading={fetchNow.isPending}
          onClick={() => fetchNow.mutate(s.id)}
        >
          Fetch
        </Button>
      ),
    },
  ];

  return (
    <Card
      size="small"
      style={{ marginBottom: 16 }}
      title={
        <Space size={6}>
          <GlobalOutlined />
          <span>From the web</span>
          {waiting > 0 && <Tag color="orange">{waiting} post(s) waiting to be read</Tag>}
        </Space>
      }
      extra={
        rows.length > 0 && (
          <Button
            size="small"
            icon={<CloudDownloadOutlined />}
            loading={fetchNow.isPending}
            onClick={() => fetchNow.mutate(undefined)}
          >
            Fetch all
          </Button>
        )
      }
    >
      {rows.length === 0 ? (
        <Alert
          type="info"
          showIcon
          message="No boards are being read"
          description={
            <>
              ship.gr's <Typography.Text code>Open Cargoes</Typography.Text> and{' '}
              <Typography.Text code>Open Ships</Typography.Text> pages carry the same circulars
              this mailbox does, pasted by the firms themselves. A desk administrator switches a
              board on here; a platform administrator adds one as a{' '}
              <Typography.Text code>Website</Typography.Text> source with the{' '}
              <Typography.Text code>ship.gr open cargoes / open ships board</Typography.Text>{' '}
              parser on the Feed tab.
            </>
          }
        />
      ) : (
        <>
          <Table<FeedSource>
            rowKey={(s) => s.id}
            size="small"
            pagination={false}
            loading={loading}
            columns={columns}
            dataSource={rows}
          />
          {rows.some((s) => s.lastError) && (
            <Space direction="vertical" size={6} style={{ width: '100%', marginTop: 12 }}>
              {rows
                .filter((s) => s.lastError)
                .map((s) => (
                  <Alert
                    key={s.id}
                    type="warning"
                    showIcon
                    message={`${s.name}: ${s.lastError}`}
                  />
                ))}
            </Space>
          )}
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Fetching stores what the page has added; reading it is the sweep's job, so a post
            arrives here first and becomes a cargo or a position when Parse now runs.
          </Typography.Text>
        </>
      )}
    </Card>
  );
}
