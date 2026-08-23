import { useEffect, useMemo, useState } from 'react';
import { Button, Descriptions, Drawer, List, Popconfirm, Select, Space, Spin, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useContactMutations, useVessel, useVesselMutations } from '../../api/hooks';
import { recordRecent } from '../../recent/store';
import ConfirmTag from '../../components/ConfirmTag';
import ContactLine from '../../components/ContactLine';
import EditToolbar, { useEditMode } from '../../components/EditToolbar';
import VesselRoleTag, { ROLE_OPTIONS } from '../../components/VesselRoleTag';
import AttachCompanyModal from './AttachCompanyModal';
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
  // Keyed on vesselId like every other edit mode in this drawer: the drawer stays mounted
  // as you move from vessel to vessel, so without the reset you would still be armed to
  // unconfirm on the next one you opened.
  const [statusEditing, setStatusEditing] = useEditMode(vesselId);

  const { setLink, removeLink } = useVesselMutations();
  const [linksEditing, setLinksEditing] = useEditMode(vesselId);
  const [linkModalOpen, setLinkModalOpen] = useState(false);
  const links = data?.links ?? [];

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
          {/* The toggle sits with the tags it governs, not on a row of its own — the same
              way the Companies list below has its own. Each Edit in this drawer belongs to
              the thing beside it, and the header's Edit (which opens the form) is a fourth
              of those rather than an exception to it. */}
          <Space style={{ marginBottom: 12 }} wrap>
            <ConfirmTag
              editing={statusEditing}
              confirmed={v.confirmed}
              confirmedAt={v.confirmedAt}
              confirmedBy={v.confirmedBy}
              loading={confirm.isPending}
              onConfirm={(body) => confirm.mutate({ id: v.id, confirmed: true, body })}
              onUnconfirm={() => confirm.mutate({ id: v.id, confirmed: false })}
            />
            {v.banned && <Tag color="red">banned</Tag>}
            <EditToolbar
              editing={statusEditing}
              onToggle={setStatusEditing}
              style={{ marginBottom: 0 }}
            />
          </Space>
          {/* One column on a phone: bordered Descriptions put label and value in the same
              row, so two of each across 360px leaves nothing legible in any of the four. */}
          <Descriptions column={{ xs: 1, sm: 2 }} size="small" bordered>
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
            Companies ({links.length})
          </Typography.Title>
          <EditToolbar editing={linksEditing} onToggle={setLinksEditing}>
            <Button size="small" icon={<PlusOutlined />} onClick={() => setLinkModalOpen(true)}>
              Attach company
            </Button>
          </EditToolbar>
          <List
            size="small"
            dataSource={links}
            locale={{ emptyText: 'No companies linked' }}
            renderItem={(l) => (
              <List.Item>
                <Space wrap size={4}>
                  <Typography.Link strong onClick={() => setCompanyId(l.companyId)}>
                    {l.companyName}
                  </Typography.Link>
                  <VesselRoleTag role={l.role} />
                  {l.cityName && <Tag>{l.cityName}</Tag>}
                  {linksEditing && (
                    <>
                      <Select
                        size="small"
                        style={{ width: 150 }}
                        value={l.role}
                        options={ROLE_OPTIONS}
                        onChange={(role) =>
                          setLink.mutate({ vesselId: v.id, companyId: l.companyId, role })
                        }
                      />
                      <Popconfirm
                        title="Detach this company?"
                        onConfirm={() => removeLink.mutate({ vesselId: v.id, companyId: l.companyId })}
                      >
                        <Button size="small" danger aria-label={`Detach ${l.companyName}`} icon={<DeleteOutlined />} />
                      </Popconfirm>
                    </>
                  )}
                </Space>
              </List.Item>
            )}
          />

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

          <AttachCompanyModal
            open={linkModalOpen}
            vesselId={v.id}
            vesselName={v.name}
            existing={links}
            onClose={() => setLinkModalOpen(false)}
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
