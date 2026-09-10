import { useState } from 'react';
import { Button, Descriptions, Drawer, Empty, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { EditOutlined, MailOutlined } from '@ant-design/icons';
import { useCargo, useCargoMutations } from '../../api/hooks';
import { useCargoSources } from '../../intake/store';
import OriginalEmail from '../../components/OriginalEmail';
import type { EmailSource } from '../../components/OriginalEmail';
import RecordHistory from '../../components/RecordHistory';
import CargoSources from './CargoSources';
import { CARGO_STATUS_META, CARGO_STATUS_OPTIONS, formatLaycan, formatPlace, formatQuantity } from './status';
import type { CargoResponse, CargoStatus } from '../../api/types';

interface Props {
  cargoId?: number;
  onClose: () => void;
  onEdit: (cargo: CargoResponse) => void;
}

/** A requirement, said in the three states it actually has. */
function requirement(value?: boolean): string {
  if (value == null) return 'not said';
  return value ? 'required' : 'not required';
}

/**
 * One cargo, read-only.
 *
 * The status dropdown is the exception, and it is here rather than only in the form for the
 * same reason the confirm tag sits on a vessel row: moving a cargo to Quoted or Fixed is the
 * single most frequent write on this screen, it changes one field, and it has its own
 * endpoint. Everything else that writes lives behind Edit.
 */
export default function CargoDrawer({ cargoId, onClose, onEdit }: Props) {
  const { data, isLoading } = useCargo(cargoId);
  const { setStatus } = useCargoMutations();
  // Every email this cargo arrived in. A merged cargo has several - that is what a merge is -
  // and the sources are not behind the parser switch, because they are part of the cargo
  // rather than part of the feature that created it.
  const { data: sources } = useCargoSources(cargoId);
  const [emailOpen, setEmailOpen] = useState(false);

  // Only the ones the mailbox still holds can be opened. A cargo somebody typed has none,
  // and the button is absent rather than present and dead.
  //
  // Falling back to the cargo's own column matters more than it looks: cargo_sources is the
  // newer per-arrival table and older rows have only source_mail_message_id on the cargo
  // itself, so without this the button would be missing on exactly the cargoes that have been
  // here longest. A cargo with both lists the sources, which are the fuller answer.
  const readable: EmailSource[] =
    (sources ?? []).filter((s) => s.mailMessageId != null).length > 0
      ? (sources ?? [])
          .filter((s) => s.mailMessageId != null)
          .map((s, i) => ({
            mailMessageId: s.mailMessageId,
            label: s.companyName ?? s.personName ?? s.fromAddress,
            when: s.reportedAt,
            current: i === 0,
          }))
      : data?.sourceMailMessageId != null
        ? [{ mailMessageId: data.sourceMailMessageId, current: true }]
        : [];

  return (
    <Drawer
      open={cargoId != null}
      onClose={onClose}
      width={640}
      title={data ? `${data.commodity}` : 'Cargo'}
      extra={
        data && (
          <Space>
            <Select<CargoStatus>
              value={data.status}
              options={CARGO_STATUS_OPTIONS}
              style={{ width: 130 }}
              loading={setStatus.isPending}
              onChange={(status) => setStatus.mutate({ id: data.id, status })}
            />
            {readable.length > 0 && (
              <Tooltip title="What a broker actually wrote. The only thing that settles whether a figure on this screen is right.">
                <Button icon={<MailOutlined />} onClick={() => setEmailOpen(true)}>
                  Original email{readable.length > 1 ? `s (${readable.length})` : ''}
                </Button>
              </Tooltip>
            )}
            <Button icon={<EditOutlined />} onClick={() => onEdit(data)}>
              Edit
            </Button>
          </Space>
        )
      }
    >
      {isLoading && <Spin />}
      {!isLoading && !data && <Empty description="This cargo is no longer on file" />}
      {data && (
        <>
          <Space wrap style={{ marginBottom: 16 }}>
            <Tag color={CARGO_STATUS_META[data.status].color}>
              {CARGO_STATUS_META[data.status].label}
            </Tag>
            {data.fromMail && <Tag color="blue">from mail</Tag>}
          </Space>
          {data.statusNote && (
            <Typography.Paragraph type="secondary">{data.statusNote}</Typography.Paragraph>
          )}

          <Descriptions column={1} size="small" bordered>
            <Descriptions.Item label="Quantity">
              {formatQuantity(data.quantity, data.quantityUnit, data.quantityTolerance)}
              {/* The matching range is shown separately and only when it exists. Its absence
                  is informative: it means the tolerance was not a percentage and nothing has
                  turned it into numbers yet, which is precisely when a hull that would have
                  worked gets left out of the suggestions. */}
              {data.quantityMin != null && (
                <Typography.Text type="secondary">
                  {' '}
                  — matching {data.quantityMin.toLocaleString()}
                  {data.quantityMax != null && `–${data.quantityMax.toLocaleString()}`}
                </Typography.Text>
              )}
              {data.quantityMin == null && data.quantityTolerance && (
                <Typography.Text type="warning"> — tolerance not read as a range</Typography.Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="Load">
              {formatPlace(data.loadPortName, data.loadPortText, data.loadAreaCode)}
            </Descriptions.Item>
            <Descriptions.Item label="Discharge">
              {formatPlace(data.dischargePortName, data.dischargePortText, data.dischargeAreaCode)}
            </Descriptions.Item>
            <Descriptions.Item label="Laycan">
              {formatLaycan(data.laycanFrom, data.laycanTo, data.laycanText)}
            </Descriptions.Item>
            {data.stowageFactor != null && (
              <Descriptions.Item label="Stowage factor">{data.stowageFactor} cbft/mt</Descriptions.Item>
            )}
            <Descriptions.Item label="Wants">
              <Space direction="vertical" size={0}>
                <span>
                  DWT {data.minDwt?.toLocaleString() ?? '—'} to {data.maxDwt?.toLocaleString() ?? '—'}
                </span>
                <span>Max draft {data.maxDraft != null ? `${data.maxDraft}m` : '—'}</span>
                <span>Max age {data.maxAgeYears ?? '—'}</span>
                <span>Gear {requirement(data.requiresGeared)}</span>
                <span>Grain fitted {requirement(data.requiresGrainFitted)}</span>
                <span>IMO fitted {requirement(data.requiresImoFitted)}</span>
                {/* Only when it disagrees with the desk. Printing the setting's own figure
                    here would read as a decision somebody made about this cargo. */}
                {data.maxBallastDays != null && (
                  <span>Ballast limit {data.maxBallastDays}d</span>
                )}
              </Space>
            </Descriptions.Item>
            {(data.freightIdea || data.commission || data.terms) && (
              <Descriptions.Item label="Commercials">
                <Space direction="vertical" size={0}>
                  {data.freightIdea && <span>Freight {data.freightIdea}</span>}
                  {data.commission && <span>Commission {data.commission}</span>}
                  {data.terms && <span>Terms {data.terms}</span>}
                  {data.loadRate && <span>Load {data.loadRate}</span>}
                  {data.dischargeRate && <span>Discharge {data.dischargeRate}</span>}
                </Space>
              </Descriptions.Item>
            )}
            {(data.chartererCompanyName || data.brokerCompanyName) && (
              <Descriptions.Item label="Counterparties">
                <Space direction="vertical" size={0}>
                  {data.chartererCompanyName && <span>Charterer: {data.chartererCompanyName}</span>}
                  {data.brokerCompanyName && <span>Broker: {data.brokerCompanyName}</span>}
                  {data.brokerPersonName && <span>Contact: {data.brokerPersonName}</span>}
                </Space>
              </Descriptions.Item>
            )}
          </Descriptions>

          {/* Notes last, after the reason the record was opened. */}
          {data.notes && (
            <>
              <Typography.Title level={5} style={{ marginTop: 24 }}>
                Notes
              </Typography.Title>
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{data.notes}</Typography.Paragraph>
            </>
          )}

          <CargoSources cargoId={data.id} />

          {/* Every arrival, with a picker when there is more than one: a cargo three brokers
              sent is one record, and which of them said what is settled by reading them. */}
          <OriginalEmail
            sources={readable}
            open={emailOpen}
            onClose={() => setEmailOpen(false)}
          />

          <RecordHistory entityType="cargo" entityId={data.id} />
        </>
      )}
    </Drawer>
  );
}
