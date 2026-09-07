import { List, Space, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import { useCargoSources } from '../../intake/store';

/**
 * Who has told us about this cargo.
 *
 * <p><b>Why a cargo has a list of senders and a vessel position does not.</b> A position is
 * already one row per report carrying its own reporter, and Open Fleet collapses them to the
 * newest per hull — the sources are the rows. A cargo is the opposite shape: several brokers
 * describe one enquiry and it has to stay one record, because the same cargo listed three
 * times is three chances to offer the same ship against it. So the merge happens on the
 * cargo and this is what keeps the provenance.
 *
 * <p>Renders nothing at all when there is one source or none. A cargo somebody typed has no
 * arrivals to list, and a heading over an empty box on every cargo would teach the reader to
 * stop looking at the one place it matters. The single-source case is covered by the "from
 * mail" tag and the broker already on the record.
 */
export default function CargoSources({ cargoId }: { cargoId: number }) {
  const { data } = useCargoSources(cargoId);
  const sources = data ?? [];
  if (sources.length < 2) return null;

  return (
    <>
      <Typography.Title level={5} style={{ marginTop: 24 }}>
        <Space>
          Also sent by
          <Tag color="purple">{sources.length} brokers</Tag>
        </Space>
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 8 }}>
        Merged into one cargo so it is offered once. Every arrival is kept.
      </Typography.Paragraph>
      <List
        size="small"
        dataSource={sources}
        renderItem={(s) => (
          <List.Item>
            <Space direction="vertical" size={0} style={{ width: '100%' }}>
              <Typography.Text>
                {s.companyName || s.fromAddress || 'Unknown sender'}
                {s.personName ? ` — ${s.personName}` : ''}
              </Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {s.reportedAt ? dayjs(s.reportedAt).format('D MMM YYYY HH:mm') : ''}
                {s.mailSubject ? ` · ${s.mailSubject}` : ''}
                {s.notes ? ` · ${s.notes}` : ''}
              </Typography.Text>
            </Space>
          </List.Item>
        )}
      />
    </>
  );
}
