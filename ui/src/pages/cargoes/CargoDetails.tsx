import { Descriptions, Space, Typography } from 'antd';
import dayjs from 'dayjs';
import { formatLaycan, formatPlace, formatQuantity } from './status';
import type { CargoResponse } from '../../api/types';

/** A requirement, said in the three states it actually has. */
function requirement(value?: boolean): string {
  if (value == null) return 'not said';
  return value ? 'required' : 'not required';
}

/**
 * What the cargo says, field by field — the body of its drawer, and the left-hand column of
 * its full tonnage list. One component for both because the second exists to check the first:
 * a ship scored against a draft limit is only as right as the limit, and the limit is only
 * as right as what the broker wrote, which sits under this in that window.
 */
export default function CargoDetails({
  cargo: data,
  hasSources,
}: {
  cargo: CargoResponse;
  /** Whether any arrival is on file, which is what makes the first date "sent" not "entered". */
  hasSources: boolean;
}) {
  return (
    <>
      <Descriptions column={1} size="small" bordered>
        {data.lastSentAt && (
          <Descriptions.Item label={data.sourceKind !== 'MANUAL' || hasSources ? 'Last sent' : 'Entered'}>
            {dayjs(data.lastSentAt).format('D MMM YYYY HH:mm')}
          </Descriptions.Item>
        )}
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
            {data.maxBallastDays != null && <span>Ballast limit {data.maxBallastDays}d</span>}
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
    </>
  );
}
