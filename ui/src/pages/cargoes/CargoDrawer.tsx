import { Button, Descriptions, Drawer, Empty, Select, Space, Spin, Tag, Typography } from 'antd';
import { EditOutlined } from '@ant-design/icons';
import { useCargo, useCargoMutations } from '../../api/hooks';
import RecordHistory from '../../components/RecordHistory';
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
                <span>Max draft {data.maxDraft ?? '—'}</span>
                <span>Max age {data.maxAgeYears ?? '—'}</span>
                <span>Gear {requirement(data.requiresGeared)}</span>
                <span>Grain fitted {requirement(data.requiresGrainFitted)}</span>
                <span>IMO fitted {requirement(data.requiresImoFitted)}</span>
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

          <RecordHistory entityType="cargo" entityId={data.id} />
        </>
      )}
    </Drawer>
  );
}
