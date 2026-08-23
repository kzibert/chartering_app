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
  const v = data?.vessel;

  const [companyId, setCompanyId] = useState<number>();
  const [companyFormOpen, setCompanyFormOpen] = useState(false);
  const [editingCompany, setEditingCompany] = useState<CompanyResponse | null>(null);

  // Owner contacts follow the same rules as the company drawer's Contacts tab:
  // read-only until Edit is on, which then reveals add/edit/delete and the
  // main / not-working toggles.
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
      // Just Edit. Ban and Delete moved inside it, where confirm went too — the header of
      // a drawer you opened to read something is no place for a one-click delete.
      extra={v && <Button onClick={() => onEdit(v)}>Edit</Button>}
    >
      {isLoading || !v ? (
        <Spin />
      ) : (
        <>
          {/* Status only. ConfirmTag without `editing` is a tag and nothing more, and the
              control for it now lives in the edit form. */}
          <Space style={{ marginBottom: 12 }} wrap>
            <ConfirmTag
              confirmed={v.confirmed}
              confirmedAt={v.confirmedAt}
              confirmedBy={v.confirmedBy}
              onConfirm={() => undefined}
              onUnconfirm={() => undefined}
            />
            {v.banned && <Tag color="red">banned</Tag>}
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
            // The nested company drawer, if one is open on the company just deleted.
            onDeleted={() => setCompanyId(undefined)}
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
