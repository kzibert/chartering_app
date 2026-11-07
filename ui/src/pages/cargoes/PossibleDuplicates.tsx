import { useState } from 'react';
import { Button, Card, Descriptions, Popconfirm, Space, Table, Tag, Typography, message } from 'antd';
import { EyeOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useIntakeMutations } from '../../intake/store';
import IntakeItemDrawer from '../intake/IntakeItemDrawer';
import type { FieldDiff, IntakeAction, IntakeItemResponse } from '../../api/intake';
import { readingLines } from './cargoReading';

interface Props {
  /** The pending duplicate questions stacked on this cargo, from usePossibleDuplicates. */
  items: IntakeItemResponse[];
}

/** Who sent one arrival, in the words the sources list uses for the same thing. */
const senderOf = (s: { senderCompanyName?: string; fromName?: string; fromAddress?: string; feedSourceName?: string }) =>
  s.senderCompanyName ?? s.fromName ?? s.fromAddress ?? s.feedSourceName ?? 'Unknown sender';

/**
 * The enquiries that may be this cargo under another name, one card each.
 *
 * <b>Why it sits on the cargo.</b> A duplicate is noticed when a second broker's email arrives,
 * and the Intake queue is where it is worked, but the decision is about this cargo: how many
 * enquiries it really is, who is working it, what a merge would change. So it is answered from
 * here, beside the cargo's own sources and figures. The Intake item stays one click away for
 * its full screen.
 *
 * <b>Nothing merges until asked.</b> Each card is one arriving reading the server has already
 * compared with this cargo. The matcher's reasons are printed in full, so a broker can disagree
 * with them. The answers are the Intake footer's own, with the same meanings: a merge fills
 * what is empty and leaves every disagreement alone, keeping separate makes the arrival a cargo
 * of its own, and discard writes nothing.
 */
export default function PossibleDuplicates({ items }: Props) {
  const { resolve } = useIntakeMutations();
  const [openId, setOpenId] = useState<number>();

  if (items.length === 0) return null;

  const answer = (item: IntakeItemResponse, action: IntakeAction) =>
    resolve.mutate(
      { id: item.id, body: { action } },
      { onSuccess: (updated) => message.success(updated.resolutionNote || 'Done.') },
    );

  // Only the card being answered shows a spinner. Resolving clears every intake query, so the
  // stack, this cargo and its sources all refetch without any further call from here.
  const busyAction = (id: number): IntakeAction | undefined =>
    resolve.isPending && resolve.variables?.id === id ? resolve.variables.body.action : undefined;

  return (
    <>
      <Typography.Title level={5} style={{ marginTop: 24 }}>
        <Space wrap>
          Possible duplicates
          <Tag color="purple">{items.length}</Tag>
        </Space>
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
        Enquiries that look like this cargo, held until somebody says which they are.
      </Typography.Paragraph>

      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        {items.map((item) => {
          const busy = busyAction(item.id);
          const lines = readingLines(item.payload?.cargo);
          const reasons = item.payload?.reasons ?? [];
          const wouldFill = item.payload?.wouldFill ?? [];
          const differing: FieldDiff[] = item.payload?.differing ?? [];
          const sources = item.sources ?? [];
          // Newest first, as the endpoint returns them; the item's own date is the fallback.
          const when = sources[0]?.receivedAt ?? item.receivedAt ?? item.createdAt;
          const senders = [...new Set(sources.map(senderOf))];

          return (
            <Card
              key={item.id}
              size="small"
              title={
                <Space size={6} wrap>
                  <Typography.Text strong>{senders.join(', ') || 'Unknown sender'}</Typography.Text>
                  {when && (
                    <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 'normal' }}>
                      {dayjs(when).format('D MMM YYYY HH:mm')}
                      {sources.length > 1 ? ` · ${sources.length} emails` : ''}
                    </Typography.Text>
                  )}
                </Space>
              }
            >
              {/* A card for an item raised by an older build has no reading to show; the
                  email is still one click away on the item itself. */}
              {lines.length > 0 ? (
                <Descriptions
                  size="small"
                  column={1}
                  items={lines.map((l) => ({ key: l.label, label: l.label, children: l.value }))}
                />
              ) : (
                <Typography.Text type="secondary">Open it to read what was sent.</Typography.Text>
              )}

              {reasons.length > 0 && (
                <>
                  <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 10 }}>
                    Matched because
                  </Typography.Text>
                  <ul style={{ margin: '4px 0 0 16px', padding: 0 }}>
                    {reasons.map((r) => (
                      <li key={r}>{r}</li>
                    ))}
                  </ul>
                </>
              )}

              {wouldFill.length > 0 && (
                <Typography.Paragraph style={{ margin: '10px 0 0' }}>
                  Merging would fill in: <Typography.Text strong>{wouldFill.join(', ')}</Typography.Text>
                </Typography.Paragraph>
              )}

              {differing.length > 0 && (
                <div style={{ marginTop: 10 }}>
                  <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 4 }}>
                    These differ — the cargo keeps what it has
                  </Typography.Text>
                  <Table<FieldDiff>
                    rowKey={(d) => d.field}
                    size="small"
                    pagination={false}
                    scroll={{ x: 'max-content' }}
                    dataSource={differing}
                    columns={[
                      { title: 'Field', dataIndex: 'label', key: 'label', width: 150 },
                      { title: 'On file', dataIndex: 'current', key: 'current' },
                      { title: 'This email', dataIndex: 'incoming', key: 'incoming' },
                    ]}
                  />
                </div>
              )}

              <Space wrap style={{ marginTop: 12 }}>
                <Button
                  type="primary"
                  loading={busy === 'ACCEPT'}
                  disabled={resolve.isPending && busy !== 'ACCEPT'}
                  onClick={() => answer(item, 'ACCEPT')}
                >
                  Same cargo — merge
                </Button>
                <Button
                  loading={busy === 'ALTERNATIVE'}
                  disabled={resolve.isPending && busy !== 'ALTERNATIVE'}
                  onClick={() => answer(item, 'ALTERNATIVE')}
                >
                  Separate cargo
                </Button>
                <Popconfirm
                  title="Discard this enquiry?"
                  description="Nothing will be written. The email stays in the log."
                  okText="Yes"
                  cancelText="No"
                  onConfirm={() => answer(item, 'DISCARD')}
                >
                  <Button danger loading={busy === 'DISCARD'} disabled={resolve.isPending && busy !== 'DISCARD'}>
                    Discard
                  </Button>
                </Popconfirm>
                <Button icon={<EyeOutlined />} onClick={() => setOpenId(item.id)}>
                  Open
                </Button>
              </Space>
            </Card>
          );
        })}
      </Space>

      {/* The full item, with every arrival and its original email. Mounted only when asked,
          for the same reason the cargo drawer mounts its other windows on demand. */}
      {openId != null && <IntakeItemDrawer itemId={openId} onClose={() => setOpenId(undefined)} />}
    </>
  );
}
