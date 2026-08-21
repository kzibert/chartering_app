import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Collapse,
  Drawer,
  List,
  Popconfirm,
  Space,
  Spin,
  Table,
  Select,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  DeleteOutlined,
  EditOutlined,
  LinkOutlined,
  PlusOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import {
  useCompany,
  useCompanyContacts,
  useCompanyVessels,
  useCompanyMutations,
  useContactMutations,
  usePeople,
  usePersonMutations,
  useVesselMutations,
} from '../../api/hooks';
import ConfirmTag from '../../components/ConfirmTag';
import ContactLine from '../../components/ContactLine';
import VesselRoleTag, { ROLE_OPTIONS } from '../../components/VesselRoleTag';
import EditToolbar, { useEditMode } from '../../components/EditToolbar';
import BanButton from '../../components/BanButton';
import GreetingName from '../../components/GreetingName';
import VesselDrawer from '../vessels/VesselDrawer';
import VesselForm from '../vessels/VesselForm';
import { LinkVesselModal } from '../vessels/VesselOwnerModals';
import ContactForm from '../contacts/ContactForm';
import PersonForm from '../people/PersonForm';
import { recordRecent } from '../../recent/store';
import type {
  CompanyResponse,
  CompanyVesselResponse,
  ContactRequest,
  ContactResponse,
  PersonResponse,
  VesselResponse,
} from '../../api/types';

type TabKey = 'vessels' | 'people' | 'contacts';

interface Props {
  companyId?: number;
  /** Tab to land on. Re-applied whenever the drawer switches company. */
  initialTab?: TabKey;
  onClose: () => void;
  onEdit: (c: CompanyResponse) => void;
}

export default function CompanyDrawer({ companyId, initialTab = 'vessels', onClose, onEdit }: Props) {
  const { data, isLoading } = useCompany(companyId);
  const { confirm, remove, ban } = useCompanyMutations();
  const c = data?.company;

  const [vesselId, setVesselId] = useState<number>();

  // Controlled so opening a person from the dashboard can land on the People tab;
  // as a side effect each company starts on its own initial tab rather than
  // inheriting whichever tab the previously viewed company was left on.
  const [tab, setTab] = useState<TabKey>(initialTab);
  useEffect(() => setTab(initialTab), [companyId, initialTab]);

  // Feeds the dashboard's "recently opened" trail.
  useEffect(() => {
    if (c) recordRecent({ kind: 'company', id: c.id, title: c.name, subtitle: c.cityName });
  }, [c?.id, c?.name, c?.cityName]); // eslint-disable-line react-hooks/exhaustive-deps

  // One form instance per drawer, driven by whichever row was clicked — rather than a
  // modal per row, which would mount hundreds of them for a company with many contacts.
  // `editing == null` while open means "add new", prefilled from the *Defaults below.
  const [vesselFormOpen, setVesselFormOpen] = useState(false);
  const [editingVessel, setEditingVessel] = useState<VesselResponse | null>(null);
  const [linkVesselOpen, setLinkVesselOpen] = useState(false);
  const [contactFormOpen, setContactFormOpen] = useState(false);
  const [editingContact, setEditingContact] = useState<ContactResponse | null>(null);
  const [contactDefaults, setContactDefaults] = useState<Partial<ContactRequest>>();
  const [personFormOpen, setPersonFormOpen] = useState(false);
  const [editingPerson, setEditingPerson] = useState<PersonResponse | null>(null);

  // Memoised so the forms' prefill effect doesn't re-run on every drawer render.
  const vesselDefaults = useMemo(() => ({ ownerId: companyId }), [companyId]);
  const personDefaults = useMemo(() => ({ companyId }), [companyId]);

  const openVesselForm = (v: VesselResponse | null) => {
    setEditingVessel(v);
    setVesselFormOpen(true);
  };
  const openPersonForm = (p: PersonResponse | null) => {
    setEditingPerson(p);
    setPersonFormOpen(true);
  };
  const openContactForm = (ct: ContactResponse | null, personId?: number) => {
    setEditingContact(ct);
    setContactDefaults(ct ? undefined : { companyId, personId });
    setContactFormOpen(true);
  };

  return (
    <Drawer
      open={companyId != null}
      // Roomy enough for the vessel table's edit-mode action buttons.
      width={720}
      title={c?.name ?? 'Company'}
      onClose={onClose}
      extra={
        c && (
          <Space>
            <Button onClick={() => onEdit(c)}>Edit</Button>
            <BanButton
              banned={c.banned}
              loading={ban.isPending}
              onToggle={(b) => ban.mutate({ id: c.id, banned: b })}
            />
            <Button danger loading={remove.isPending} onClick={() => remove.mutate(c.id, { onSuccess: onClose })}>
              Delete
            </Button>
          </Space>
        )
      }
    >
      {isLoading || !c ? (
        <Spin />
      ) : (
        <>
          <Space style={{ marginBottom: 12 }} wrap>
            <ConfirmTag
              confirmed={c.confirmed}
              confirmedAt={c.confirmedAt}
              confirmedBy={c.confirmedBy}
              loading={confirm.isPending}
              onConfirm={(body) => confirm.mutate({ id: c.id, confirmed: true, body })}
              onUnconfirm={() => confirm.mutate({ id: c.id, confirmed: false })}
            />
            {c.noWorkingEmail && (
              <Tooltip title="Every email address on file for this company is flagged not working">
                <Tag color="red">no working email</Tag>
              </Tooltip>
            )}
            {c.banned && <Tag color="red">banned</Tag>}
            {c.cityName && <Tag>{c.cityName}</Tag>}
            {c.solo && <Tag color="geekblue">solo entrepreneur</Tag>}
            {c.shipowner && <Tag color="blue">owner</Tag>}
            {c.charterer && <Tag color="green">charterer</Tag>}
            {c.broker && <Tag color="gold">broker</Tag>}
            {c.agent && <Tag color="purple">agent</Tag>}
          </Space>
          {c.notes && (
            <Typography.Paragraph style={{ marginBottom: 12, whiteSpace: 'pre-wrap' }}>
              <Typography.Text type="secondary">Notes: </Typography.Text>
              {c.notes}
            </Typography.Paragraph>
          )}
          <Tabs
            activeKey={tab}
            onChange={(k) => setTab(k as TabKey)}
            items={[
              {
                key: 'vessels',
                label: 'Vessels',
                children: (
                  <CompanyVesselsTab
                    id={c.id}
                    onOpenVessel={setVesselId}
                    onAddVessel={() => openVesselForm(null)}
                    onLinkVessel={() => setLinkVesselOpen(true)}
                    onEditVessel={openVesselForm}
                  />
                ),
              },
              {
                key: 'people',
                label: 'People',
                children: (
                  <CompanyPeopleTab
                    id={c.id}
                    onAddPerson={() => openPersonForm(null)}
                    onEditPerson={openPersonForm}
                    onAddContact={(personId) => openContactForm(null, personId)}
                    onEditContact={(ct) => openContactForm(ct)}
                  />
                ),
              },
              {
                key: 'contacts',
                label: 'Contacts',
                children: (
                  <CompanyContactsTab
                    id={c.id}
                    onAddContact={() => openContactForm(null)}
                    onEditContact={(ct) => openContactForm(ct)}
                  />
                ),
              },
            ]}
          />

          <VesselDrawer
            vesselId={vesselId}
            onClose={() => setVesselId(undefined)}
            onEdit={openVesselForm}
          />
          <VesselForm
            open={vesselFormOpen}
            editing={editingVessel}
            defaults={vesselDefaults}
            onClose={() => setVesselFormOpen(false)}
          />
          <LinkVesselModal
            open={linkVesselOpen}
            companyId={c.id}
            companyName={c.name}
            onClose={() => setLinkVesselOpen(false)}
          />
          <ContactForm
            open={contactFormOpen}
            editing={editingContact}
            defaults={contactDefaults}
            onClose={() => setContactFormOpen(false)}
          />
          <PersonForm
            open={personFormOpen}
            editing={editingPerson}
            defaults={personDefaults}
            onClose={() => setPersonFormOpen(false)}
          />
        </>
      )}
    </Drawer>
  );
}

function CompanyVesselsTab({
  id,
  onOpenVessel,
  onAddVessel,
  onLinkVessel,
  onEditVessel,
}: {
  id: number;
  onOpenVessel: (vesselId: number) => void;
  onAddVessel: () => void;
  onLinkVessel: () => void;
  onEditVessel: (v: VesselResponse) => void;
}) {
  const { data, isLoading } = useCompanyVessels(id);
  const { remove, setLink, removeLink } = useVesselMutations();
  const [editMode, setEditMode] = useEditMode(id);

  const columns: ColumnsType<CompanyVesselResponse> = [
    {
      title: 'Name',
      key: 'name',
      render: (_, r) => (
        <Typography.Link onClick={() => onOpenVessel(r.vessel.id)}>{r.vessel.name}</Typography.Link>
      ),
    },
    {
      title: 'Role',
      key: 'role',
      width: 150,
      // In edit mode the tag becomes the control: changing it re-links the vessel.
      render: (_, r) =>
        editMode ? (
          <Select
            size="small"
            style={{ width: 140 }}
            value={r.role}
            options={ROLE_OPTIONS}
            onChange={(role) => setLink.mutate({ vesselId: r.vessel.id, companyId: id, role })}
          />
        ) : (
          <VesselRoleTag role={r.role} />
        ),
    },
    { title: 'DWT', key: 'dwt', render: (_, r) => r.vessel.deadweightTonnage },
    { title: 'Year', key: 'year', render: (_, r) => r.vessel.yearBuilt },
    { title: 'Type', key: 'type', render: (_, r) => r.vessel.vesselType },
  ];
  if (editMode) {
    columns.push({
      title: 'Actions',
      key: 'actions',
      width: 200,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => onEditVessel(r.vessel)}>Edit</Button>
          <Tooltip title="Detach this vessel from this company (the vessel itself is kept)">
            <Popconfirm
              title="Detach this vessel?"
              onConfirm={() => removeLink.mutate({ vesselId: r.vessel.id, companyId: id })}
            >
              <Button size="small" icon={<SwapOutlined />}>Detach</Button>
            </Popconfirm>
          </Tooltip>
          <Popconfirm
            title="Delete this vessel?"
            description="This removes the vessel from the database entirely."
            onConfirm={() => remove.mutate(r.vessel.id)}
          >
            <Button size="small" danger>Delete</Button>
          </Popconfirm>
        </Space>
      ),
    });
  }

  return (
    <>
      <EditToolbar editing={editMode} onToggle={setEditMode}>
        <Button size="small" icon={<PlusOutlined />} onClick={onAddVessel}>
          Add new
        </Button>
        <Tooltip title="Attach a vessel that already exists in the database">
          <Button size="small" icon={<LinkOutlined />} onClick={onLinkVessel}>
            Add existing
          </Button>
        </Tooltip>
      </EditToolbar>
      <Table<CompanyVesselResponse>
        rowKey={(r) => r.vessel.id}
        size="small"
        loading={isLoading}
        dataSource={data ?? []}
        pagination={false}
        columns={columns}
      />
    </>
  );
}

function CompanyContactsTab({
  id,
  onAddContact,
  onEditContact,
}: {
  id: number;
  onAddContact: () => void;
  onEditContact: (ct: ContactResponse) => void;
}) {
  const { data, isLoading } = useCompanyContacts(id);
  const { remove } = useContactMutations();
  const [editMode, setEditMode] = useEditMode(id);

  return (
    <>
      <EditToolbar editing={editMode} onToggle={setEditMode}>
        <Button size="small" icon={<PlusOutlined />} onClick={onAddContact}>
          Add contact
        </Button>
      </EditToolbar>
      <List
        size="small"
        loading={isLoading}
        dataSource={data ?? []}
        locale={{ emptyText: 'No contacts' }}
        renderItem={(ct) => (
          <ContactLine
            ct={ct}
            editing={editMode}
            onEdit={onEditContact}
            onDelete={(target) => remove.mutate(target.id)}
          />
        )}
      />
    </>
  );
}

/** People at the company: name + copiable greeting shown compactly, click to expand contacts. */
function CompanyPeopleTab({
  id,
  onAddPerson,
  onEditPerson,
  onAddContact,
  onEditContact,
}: {
  id: number;
  onAddPerson: () => void;
  onEditPerson: (p: PersonResponse) => void;
  onAddContact: (personId: number) => void;
  onEditContact: (ct: ContactResponse) => void;
}) {
  const { data: people, isLoading: loadingPeople } = usePeople(id);
  const { data: contacts, isLoading: loadingContacts } = useCompanyContacts(id);
  const { remove: removePerson } = usePersonMutations();
  const { remove: removeContact } = useContactMutations();
  const [editMode, setEditMode] = useEditMode(id);

  const byPerson = new Map<number, ContactResponse[]>();
  // Addresses on the company and on nobody — chartering@, ops@, the switchboard. They have
  // no person to sit under, and dropping them here is how one goes missing: the row is on
  // the Contacts tab, but this is the tab somebody opens to ask "who do we know there".
  const companyWide: ContactResponse[] = [];
  (contacts ?? []).forEach((ct) => {
    if (ct.personId == null) {
      companyWide.push(ct);
      return;
    }
    const list = byPerson.get(ct.personId) ?? [];
    list.push(ct);
    byPerson.set(ct.personId, list);
  });

  return (
    <>
      <EditToolbar editing={editMode} onToggle={setEditMode}>
        <Button size="small" icon={<PlusOutlined />} onClick={onAddPerson}>
          Add person
        </Button>
      </EditToolbar>
      {loadingPeople || loadingContacts ? (
        <Spin />
      ) : (
        <>
          {companyWide.length > 0 && (
            <div style={{ marginBottom: 12 }}>
              <Space size={4} style={{ marginBottom: 4 }}>
                <strong>The company itself</strong>
                <Tooltip title="Addresses and numbers that belong to the company rather than to any one person — a chartering@ or ops@ desk. Circulars to them open with a general greeting unless the contact carries its own.">
                  <Typography.Text type="secondary">
                    {companyWide.length} contact{companyWide.length === 1 ? '' : 's'}
                  </Typography.Text>
                </Tooltip>
              </Space>
              <List
                size="small"
                dataSource={companyWide}
                renderItem={(ct) => (
                  <ContactLine
                    ct={ct}
                    editing={editMode}
                    onEdit={onEditContact}
                    onDelete={(target) => removeContact.mutate(target.id)}
                  />
                )}
              />
            </div>
          )}
          {!people || people.length === 0 ? (
            <Typography.Text type="secondary">No people.</Typography.Text>
          ) : (
        <Collapse
          accordion
          // Expanding a panel is how you look at a person here, so that is what the
          // dashboard's trail records. Collapsing yields no key and records nothing.
          onChange={(key) => {
            const openId = Number(Array.isArray(key) ? key[0] : key);
            const p = people.find((x) => x.id === openId);
            if (p) {
              recordRecent({
                kind: 'person',
                id: p.id,
                title: p.fullName,
                subtitle: p.companyName,
                companyId: p.companyId,
              });
            }
          }}
          items={people.map((p) => {
            const personContacts = byPerson.get(p.id) ?? [];
            return {
              key: String(p.id),
              label: (
                <Space wrap>
                  <strong>{p.fullName}</strong>
                  <GreetingName title={p.title} name={p.greetingName} type="success" />
                  <Typography.Text type="secondary">
                    {personContacts.length} contact{personContacts.length === 1 ? '' : 's'}
                  </Typography.Text>
                  {editMode && (
                    // The label is the Collapse's own toggle, and the confirm popup is a
                    // React child of this span — without stopping propagation here, both
                    // the buttons and their confirmations would expand/collapse the panel.
                    <span onClick={(e) => e.stopPropagation()}>
                      <Space size={4}>
                        <Tooltip title="Edit person">
                          <Button
                            size="small"
                            aria-label={`Edit ${p.fullName}`}
                            icon={<EditOutlined />}
                            onClick={() => onEditPerson(p)}
                          />
                        </Tooltip>
                        <Tooltip title="Add contact for this person">
                          <Button
                            size="small"
                            aria-label={`Add contact for ${p.fullName}`}
                            icon={<PlusOutlined />}
                            onClick={() => onAddContact(p.id)}
                          />
                        </Tooltip>
                        <Popconfirm
                          title="Delete this person?"
                          onConfirm={() => removePerson.mutate(p.id)}
                        >
                          <Button
                            size="small"
                            danger
                            aria-label={`Delete ${p.fullName}`}
                            icon={<DeleteOutlined />}
                          />
                        </Popconfirm>
                      </Space>
                    </span>
                  )}
                </Space>
              ),
              children: personContacts.length ? (
                <List
                  size="small"
                  dataSource={personContacts}
                  renderItem={(ct) => (
                    <ContactLine
                      ct={ct}
                      showGreeting={false}
                      editing={editMode}
                      onEdit={onEditContact}
                      onDelete={(target) => removeContact.mutate(target.id)}
                    />
                  )}
                />
              ) : (
                <Typography.Text type="secondary">No contacts</Typography.Text>
              ),
            };
          })}
        />
          )}
        </>
      )}
    </>
  );
}
