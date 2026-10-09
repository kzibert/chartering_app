import { Alert, App, Button, Card, Progress, Space, Tag, Tooltip, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { isAxiosError } from 'axios';
import dayjs from 'dayjs';
import { useEmbeddingStatus, useAnalysisMutations } from '../../analysis/store';
import { useIsMobile } from '../../responsive/useIsMobile';

/**
 * The retrieval index: a vector per labelled sample, so the parser can be shown the most
 * similar examples before an email it is reading.
 *
 * Built here rather than on the way in because a vector is computed from a sample's text, and
 * the text is what the labelling changes — a sample edited last week is stale until somebody
 * indexes again. The stale count is the thing to read; "Index corpus" is only there to clear it.
 *
 * <p>EMBEDDING_URL unset is the normal state of a deployment that has not built the index yet,
 * so it is a note rather than an error. The card still says what the index would hold.
 */
/** Mounted only once the workbench is known to be on, so the status is asked unconditionally. */
export default function RetrievalIndexCard() {
  const { message: toast } = App.useApp();
  const isMobile = useIsMobile();
  const query = useEmbeddingStatus(true);
  const { indexEmbeddings } = useAnalysisMutations();

  const s = query.data;
  const running = s?.running === true;
  const hasError = !!s?.lastError;

  // The server answers 409 for a run already going on this desk, and 503 with its reason when
  // EMBEDDING_URL is missing. Both are worded here; the global tray is quiet for this call.
  const start = () =>
    indexEmbeddings.mutate(undefined, {
      onError: (err) => {
        const status = isAxiosError(err) ? err.response?.status : undefined;
        if (status === 409) {
          toast.info('Already indexing');
        } else if (status === 503) {
          toast.warning(
            (isAxiosError(err) && (err.response?.data as { message?: string })?.message) ||
              'Retrieval is not configured on this deployment',
          );
        } else {
          toast.error('Could not start indexing');
        }
      },
    });

  const buttonReason = running
    ? 'Indexing is already running'
    : s && s.stale === 0
      ? 'Every labelled sample already has a current vector'
      : undefined;

  return (
    <Card
      size="small"
      style={{ marginBottom: 16 }}
      loading={query.isLoading}
      title={
        <Space wrap>
          Retrieval index
          {s?.model && <Tag>{s.model}</Tag>}
        </Space>
      }
      extra={
        s?.enabled ? (
          <Tooltip title={buttonReason}>
            <span>
              <Button
                icon={<ReloadOutlined />}
                loading={indexEmbeddings.isPending || running}
                disabled={!!buttonReason}
                onClick={start}
                block={isMobile}
              >
                {running ? 'Indexing…' : 'Index corpus'}
              </Button>
            </span>
          </Tooltip>
        ) : undefined
      }
    >
      {s && !s.enabled && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
          Set <Typography.Text code>EMBEDDING_URL</Typography.Text> (see{' '}
          <Typography.Text code>.env.example</Typography.Text>) to enable retrieval.
        </Typography.Paragraph>
      )}

      {s?.enabled && (
        <Space direction="vertical" size={8} style={{ width: '100%' }}>
          <Space size={8} wrap>
            <Typography.Text>
              Indexed <b>{s.indexed}</b> of <b>{s.ready}</b> labelled (READY) samples
            </Typography.Text>
            {s.stale > 0 && <Tag color="orange">{s.stale} stale</Tag>}
          </Space>

          {s.lastFinishedAt && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Last run finished {dayjs(s.lastFinishedAt).format('D MMM YY HH:mm')}
            </Typography.Text>
          )}

          {running && (
            <Progress
              percent={s.total > 0 ? Math.round((s.done / s.total) * 100) : 0}
              format={() => `${s.done} of ${s.total}`}
            />
          )}

          {hasError && (
            <Alert
              type="warning"
              showIcon
              message="The last run stopped early"
              description={s.lastError}
            />
          )}
        </Space>
      )}
    </Card>
  );
}
