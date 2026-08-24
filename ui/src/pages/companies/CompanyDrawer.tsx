import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Drawer,
  List,
  Popconfirm,
  Space,
  Spin,
  Select,
  Tabs,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  LinkOutlined,
  PlusOutlined,
  SwapOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import ResponsiveTable from '../../components/ResponsiveTable';
import {
  useCompany,
  useCompanyContacts,
  useCompanyVessels,
  useContactMutations,
  useVesselMutations,
} from '../../api/hooks';
import ConfirmTag from '../../components/ConfirmTag';
import RecordHistory from '../../components/RecordHistory';
import ContactLine from '../../components/ContactLine';
import { ContactRowExpansion } from '../../components/ContactRowExpansion';
import CompanyPeopleTab from './CompanyPeopleTab';
import VesselRoleTag, { ROLE_OPTIONS } from '../../components/VesselRoleTag';
import EditToolbar, { useEditMode } from '../../components/EditToolbar';
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

type TabKey = 'vessels' | 'people' | 'contacts' | 'history';

interface Props {
  companyId?: number;
  /** Tab to land on. Re-applied whenever the drawer switches company. */
  initialTab?: TabKey;
  onClose: () => void;
  onEdit: (c: CompanyResponse) => void;
}

export default function CompanyDrawer({ companyId, initialTab = 'vessels', onClose, onEdit }: Props) {
  const { data, isLoading } = useCompany(companyId);
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
      // Just Edit. Ban and Delete moved inside it, where confirm went too — the header of
      // a drawer you opened to read something is no place for a one-click delete.
      extra={c && <Button onClick={() => onEdit(c)}>Edit</Button>}
    >
      {isLoading || !c ? (
        <Spin />
      ) : (
        <>
          {/* Status only. ConfirmTag without `editing` is a tag and nothing more, and the
              control for it now lives in the edit form. */}
          <Space style={{ marginBottom: 12 }} wrap>
            <ConfirmTag
              confirmed={c.confirmed}
              confirmedAt={c.confirmedAt}
              confirmedBy={c.confirmedBy}
              onConfirm={() => undefined}
              onUnconfirm={() => undefined}
            />
            {c.noWorkingEmail && (
              <Tooltip title="Every email address on file for this company is flagged not working">
                <Tag color="red">no working email</Tag>
              </Tooltip>
            )}
            {c.banned && <Tag color="red">banned</Tag>}
            {(c.cityName || c.country) && (
              <Tag>{[c.cityName, c.country].filter(Boolean).join(', ')}</Tag>
            )}
            {c.solo && <Tag color="geekblue">solo entrepreneur</Tag>}
            {c.shipowner && <Tag color="blue">owner</Tag>}
            {c.charterer && <Tag color="green">charterer</Tag>}
            {c.broker && <Tag color="gold">broker</Tag>}
            {c.agent && <Tag color="purple">agent</Tag>}
          </Space>
          {/* The scheme is added here rather than stored: it is the one part of a website
              that never tells you anything, and a stored "https://" is a thing to strip
              every time the value is shown or compared. */}
          {c.website && (
            <Typography.Paragraph style={{ marginBottom: 8 }}>
              <Typography.Link
                href={/^https?:\/\//i.test(c.website) ? c.website : `https://${c.website}`}
                target="_blank"
                rel="noreferrer noopener"
              >
                {c.website}
              </Typography.Link>
            </Typography.Paragraph>
          )}
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
              {
                key: 'history',
                label: 'History',
                // The company's own record only. Its people and their addresses each keep
                // their own history under their own record, which is where somebody looking
                // for "who changed this phone number" would go — folding them all in here
                // would bury a rename of the company under a hundred contact edits.
                children: (
                  <RecordHistory
                    entityType="company"
                    entityId={c.id}
                    note="Changes to the company record itself. Its people and their addresses each keep their own history, under that record."
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
            // The nested vessel drawer, if one is open on the vessel just deleted.
            onDeleted={() => setVesselId(undefined)}
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
      <ResponsiveTable<CompanyVesselResponse>
        rowKey={(r) => r.vessel.id}
        size="small"
        loading={isLoading}
        dataSource={data ?? []}
        pagination={false}
        columns={columns}
        mobile={{
          // A link, not a row click: on the desktop table only the name opens the vessel,
          // and making the whole card a target would collide with the buttons on it.
          title: (r) => (
            <Typography.Link onClick={() => onOpenVessel(r.vessel.id)}>
              {r.vessel.name}
            </Typography.Link>
          ),
          subtitle: (r) =>
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
          fields: (r) => [
            r.vessel.deadweightTonnage != null && {
              label: 'DWT',
              value: r.vessel.deadweightTonnage,
            },
            r.vessel.yearBuilt != null && { label: 'Year', value: r.vessel.yearBuilt },
            r.vessel.vesselType != null && { label: 'Type', value: r.vessel.vesselType },
          ],
          actions: (r) =>
            editMode ? (
              <Space size={4} wrap>
                <Button size="small" onClick={() => onEditVessel(r.vessel)}>Edit</Button>
                <Popconfirm
                  title="Detach this vessel?"
                  onConfirm={() => removeLink.mutate({ vesselId: r.vessel.id, companyId: id })}
                >
                  <Button size="small" icon={<SwapOutlined />}>Detach</Button>
                </Popconfirm>
                <Popconfirm
                  title="Delete this vessel?"
                  description="This removes the vessel from the database entirely."
                  onConfirm={() => remove.mutate(r.vessel.id)}
                >
                  <Button size="small" danger>Delete</Button>
                </Popconfirm>
              </Space>
            ) : null,
        }}
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
      <ContactRowExpansion>
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
      </ContactRowExpansion>
    </>
  );
}
