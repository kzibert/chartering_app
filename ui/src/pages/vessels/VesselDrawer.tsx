import { useEffect, useMemo, useState } from 'react';
import { Button, Card, Descriptions, Drawer, List, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { useContactMutations, useVessel, useVesselMutations } from '../../api/hooks';
import { recordRecent } from '../../recent/store';
import ConfirmTag from '../../components/ConfirmTag';
import ContactLine from '../../components/ContactLine';
import EditToolbar, { useEditMode } from '../../components/EditToolbar';
import BanButton from '../../components/BanButton';
import CompanyDrawer from '../companies/CompanyDrawer';
import CompanyForm from '../companies/CompanyForm';
import ContactForm from '../contacts/ContactForm';
import type { CompanyResponse, ContactResponse, VesselResponse } from '../../api/types';

interface Props {
  vesselId?: number;
  onClose: () => void;
  onEdit: (v: VesselResponse) => void;
}

export default function VesselDrawer({ vesselId, onClose, onEdit }: Props) {
  const { data, isLoading } = useVessel(vesselId);
  const { confirm, remove, ban } = useVesselMutations();
  const v = data?.vessel;

  const [companyId, setCompanyId] = useState<number>();
  const [companyFormOpen, setCompanyFormOpen] = useState(false);
  const [editingCompany, setEditingCompany] = useState<CompanyResponse | null>(null);

  // Owner contacts follow the same rules as the company drawer's Contacts tab:
  // read-only until Edit is on, which then reveals add/edit/delete and the
  // main / not-working toggles.
  const { remove: removeContact } = useContactMutations();
  const [contactsEditing, setContactsEditing] = useEditMode(vesselId);
  const [contactFormOpen, setContactFormOpen] = useState(false);
  const [editingContact, setEditingContact] = useState<ContactResponse | null>(null);

  const ownerId = data?.owner?.id;
  // A contact added here belongs to the owner company — that is what this list shows.
  const contactDefaults = useMemo(() => ({ companyId: ownerId }), [ownerId]);

  const openContactForm = (ct: ContactResponse | null) => {
    setEditingContact(ct);
    setContactFormOpen(true);
  };

  // Feeds the dashboard's "recently opened" trail.
  useEffect(() => {
    if (v) recordRecent({ kind: 'vessel', id: v.id, title: v.name, subtitle: v.ownerName });
  }, [v?.id, v?.name, v?.ownerName]); // eslint-disable-line react-hooks/exhaustive-deps

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
            <BanButton
              banned={v.banned}
              loading={ban.isPending}
              onToggle={(b) => ban.mutate({ id: v.id, banned: b })}
            />
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
          <Space style={{ marginBottom: 12 }} wrap>
            <ConfirmTag
              confirmed={v.confirmed}
              confirmedAt={v.confirmedAt}
              confirmedBy={v.confirmedBy}
              loading={confirm.isPending}
              onConfirm={(body) => confirm.mutate({ id: v.id, confirmed: true, body })}
              onUnconfirm={() => confirm.mutate({ id: v.id, confirmed: false })}
            />
            {v.banned && <Tag color="red">banned</Tag>}
          </Space>
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
          {v.notes && (
            <Typography.Paragraph style={{ marginTop: 16, whiteSpace: 'pre-wrap' }}>
              <Typography.Text type="secondary">Notes: </Typography.Text>
              {v.notes}
            </Typography.Paragraph>
          )}

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
          <EditToolbar editing={contactsEditing} onToggle={setContactsEditing}>
            <Tooltip title={ownerId ? '' : 'Link an owner company first'}>
              <Button
                size="small"
                icon={<PlusOutlined />}
                disabled={!ownerId}
                onClick={() => openContactForm(null)}
              >
                Add contact
              </Button>
            </Tooltip>
          </EditToolbar>
          <List
            size="small"
            dataSource={data?.ownerContacts ?? []}
            locale={{ emptyText: 'No contacts' }}
            renderItem={(c) => (
              <ContactLine
                ct={c}
                editing={contactsEditing}
                onEdit={openContactForm}
                onDelete={(target) => removeContact.mutate(target.id)}
              />
            )}
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
          <ContactForm
            open={contactFormOpen}
            editing={editingContact}
            defaults={contactDefaults}
            onClose={() => setContactFormOpen(false)}
          />
        </>
      )}
    </Drawer>
  );
}
