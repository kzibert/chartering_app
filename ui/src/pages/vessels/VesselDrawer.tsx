import { useState } from 'react';
import { Button, Card, Descriptions, Drawer, List, Space, Spin, Tag, Typography } from 'antd';
import { useVessel, useVesselMutations } from '../../api/hooks';
import ConfirmTag from '../../components/ConfirmTag';
import ContactLine from '../../components/ContactLine';
import CompanyDrawer from '../companies/CompanyDrawer';
import CompanyForm from '../companies/CompanyForm';
import type { CompanyResponse, VesselResponse } from '../../api/types';

interface Props {
  vesselId?: number;
  onClose: () => void;
  onEdit: (v: VesselResponse) => void;
}

export default function VesselDrawer({ vesselId, onClose, onEdit }: Props) {
  const { data, isLoading } = useVessel(vesselId);
  const { confirm, remove } = useVesselMutations();
  const v = data?.vessel;

  const [companyId, setCompanyId] = useState<number>();
  const [companyFormOpen, setCompanyFormOpen] = useState(false);
  const [editingCompany, setEditingCompany] = useState<CompanyResponse | null>(null);

  return (
    <Drawer
      open={vesselId != null}
      width={560}
      title={v?.name ?? 'Vessel'}
      onClose={onClose}
      extra={
        v && (
          <Space>
            <Button onClick={() => onEdit(v)}>Edit</Button>
            <Button
              danger
              loading={remove.isPending}
              onClick={() => remove.mutate(v.id, { onSuccess: onClose })}
            >
              Delete
            </Button>
          </Space>
        )
      }
    >
      {isLoading || !v ? (
        <Spin />
      ) : (
        <>
          <div style={{ marginBottom: 12 }}>
            <ConfirmTag
              confirmed={v.confirmed}
              confirmedAt={v.confirmedAt}
              confirmedBy={v.confirmedBy}
              loading={confirm.isPending}
              onConfirm={(body) => confirm.mutate({ id: v.id, confirmed: true, body })}
              onUnconfirm={() => confirm.mutate({ id: v.id, confirmed: false })}
            />
          </div>
          <Descriptions column={2} size="small" bordered>
            <Descriptions.Item label="IMO">{v.imoNumber ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Year">{v.yearBuilt ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="DWT">{v.deadweightTonnage ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="DWCC">{v.deadweightCargoCapacity ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Grain m³">{v.grainCapacityM3 ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Bale m³">{v.baleCapacityM3 ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Max draft">{v.maximumDraft ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Type">{v.vesselType ?? '—'}</Descriptions.Item>
            <Descriptions.Item label="Flag">{v.flag ?? '—'}</Descriptions.Item>
          </Descriptions>

          <Typography.Title level={5} style={{ marginTop: 20 }}>
            Owner
          </Typography.Title>
          {data?.owner ? (
            <Card size="small">
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Space>
                  <Typography.Link strong onClick={() => setCompanyId(data.owner!.id)}>
                    {data.owner.name}
                  </Typography.Link>
                  {data.owner.cityName && <Tag>{data.owner.cityName}</Tag>}
                </Space>
                <Space wrap>
                  {data.owner.shipowner && <Tag color="blue">owner</Tag>}
                  {data.owner.charterer && <Tag color="green">charterer</Tag>}
                  {data.owner.broker && <Tag color="gold">broker</Tag>}
                  {data.owner.agent && <Tag color="purple">agent</Tag>}
                </Space>
              </Space>
            </Card>
          ) : (
            <Typography.Text type="secondary">No owner linked.</Typography.Text>
          )}

          <Typography.Title level={5} style={{ marginTop: 20 }}>
            Owner contacts ({data?.ownerContacts.length ?? 0})
          </Typography.Title>
          <List
            size="small"
            dataSource={data?.ownerContacts ?? []}
            locale={{ emptyText: 'No contacts' }}
            renderItem={(c) => <ContactLine ct={c} />}
          />

          <CompanyDrawer
            companyId={companyId}
            onClose={() => setCompanyId(undefined)}
            onEdit={(c) => { setEditingCompany(c); setCompanyFormOpen(true); }}
          />
          <CompanyForm
            open={companyFormOpen}
            editing={editingCompany}
            onClose={() => setCompanyFormOpen(false)}
          />
        </>
      )}
    </Drawer>
  );
}
