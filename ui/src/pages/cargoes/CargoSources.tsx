import { Button, List, Space, Tag, Typography } from 'antd';
import { MailOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import type { CargoSourceResponse } from '../../api/intake';

/**
 * Who has sent us this cargo, and the email each of them sent.
 *
 * <p><b>Why a cargo has a list of senders and a vessel position does not.</b> A position is
 * already one row per report carrying its own reporter, and Open Fleet collapses them to the
 * newest per hull — the sources are the rows. A cargo is the opposite shape: several brokers
 * describe one enquiry and it has to stay one record, because the same cargo listed three
 * times is three chances to offer the same ship against it. So the merge happens on the
 * cargo and this is what keeps the provenance.
 *
 * <p>Shown from one source up. It used to appear only once a cargo had been merged, on the
 * argument that the broker on the record covered the single case — but that field is who the
 * cargo is worked through and a person may change it, while "who sent me this, from which
 * address" is a question about the arrival, and it is asked of a cargo one broker sent as
 * often as of one five did. A cargo somebody typed has no arrivals and still renders nothing.
 */
export default function CargoSources({
  sources,
  onRead,
}: {
  /** Newest first, as the endpoint returns them. */
  sources: CargoSourceResponse[];
  /** Open the original email of one arrival. */
  onRead: (mailMessageId: number) => void;
}) {
  if (sources.length === 0) return null;
  // By address first, as the list counts: one broker's two emails can resolve to his firm on
  // one and to nothing on the other, and are still one sender.
  const firms = new Set(
    sources.map((s) => (s.fromAddress ?? s.companyName ?? s.personName ?? '').toLowerCase()),
  ).size;

  return (
    <>
      <Typography.Title level={5} style={{ marginTop: 24 }}>
        <Space>
          Sent by
          <Tag color="purple">
            {firms} {firms === 1 ? 'sender' : 'senders'}
            {sources.length > firms ? ` · ${sources.length} emails` : ''}
          </Tag>
        </Space>
      </Typography.Title>
      {sources.length > 1 && (
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
          Merged into one cargo so it is offered once. Every arrival is kept, newest first.
        </Typography.Paragraph>
      )}
      <List
        size="small"
        dataSource={sources}
        renderItem={(s) => (
          <List.Item
            actions={
              s.mailMessageId != null
                ? [
                    <Button
                      key="read"
                      size="small"
                      icon={<MailOutlined />}
                      onClick={() => onRead(s.mailMessageId!)}
                    >
                      Read
                    </Button>,
                  ]
                : undefined
            }
          >
            <Space direction="vertical" size={0} style={{ width: '100%', minWidth: 0 }}>
              <Typography.Text strong>
                {s.companyName || s.personName || s.fromAddress || 'Unknown sender'}
              </Typography.Text>
              {/* The address is printed even when the firm resolved: a desk address and the
                  person who actually wrote are different facts, and the second is usually the
                  one to reply to. */}
              {(s.personName && s.companyName) || s.fromAddress ? (
                <Typography.Text style={{ fontSize: 13 }}>
                  {s.companyName && s.personName ? `${s.personName} ` : ''}
                  {s.fromAddress && (
                    <Typography.Text copyable={{ text: s.fromAddress }} type="secondary">
                      {s.fromAddress}
                    </Typography.Text>
                  )}
                </Typography.Text>
              ) : null}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {s.reportedAt ? dayjs(s.reportedAt).format('D MMM YYYY HH:mm') : ''}
                {s.mailSubject ? ` · ${s.mailSubject}` : ''}
                {s.mailMessageId == null ? ' · no longer in the mailbox' : ''}
                {s.notes ? ` · ${s.notes}` : ''}
              </Typography.Text>
            </Space>
          </List.Item>
        )}
      />
    </>
  );
}
