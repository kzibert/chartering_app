import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Checkbox,
  Col,
  Form,
  Input,
  List,
  Row,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { ImportOutlined, MailOutlined, PhoneOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { usePeopleSearch, useContactMutations } from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import ResponsiveTable from '../../components/ResponsiveTable';
import FilterPanel, { countActiveFilters } from '../../components/FilterPanel';
import { usePersistedFilters } from '../../components/usePersistedState';
import { CONFIRMED_OPTIONS } from '../../components/filterOptions';
import CompanySelect from '../../components/CompanySelect';
import ContactLine from '../../components/ContactLine';
import AddToListActions from '../../components/AddToListActions';
import { collectApi } from '../../api/circulations';
import EditToolbar, { useEditMode } from '../../components/EditToolbar';
import GreetingName from '../../components/GreetingName';
import ImportContactsModal from './ImportContactsModal';
import PersonForm from './PersonForm';
import PersonDrawer from './PersonDrawer';
import CompanyDrawer from '../companies/CompanyDrawer';
import CompanyForm from '../companies/CompanyForm';
import ContactForm from '../contacts/ContactForm';
import type {
  CompanyResponse,
  ContactRequest,
  ContactResponse,
  PeopleFilter,
  PersonDetailResponse,
  PersonResponse,
} from '../../api/types';

/**
 * People and their contacts in one place — this page absorbed what used to be a separate
 * flat Contacts tab. Every contact in the database belongs to a person, and its company
 * always matches that person's, so grouping by person loses nothing: the contact search
 * still finds an address, it just hands back the person who owns it.
 */
export default function PeoplePage() {
  const [form] = Form.useForm();
  const [filters, setFilters] = usePersistedFilters<Partial<PeopleFilter>>('people', form);
  const tc = useTableControls({ size: 20 }, 'people');

  const query = usePeopleSearch({ ...filters, page: tc.state.page, size: tc.state.size });
  const { remove: removeContact } = useContactMutations();

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<PersonResponse | null>(null);
  const [selectedId, setSelectedId] = useState<number>();
  const [companyDrawerId, setCompanyDrawerId] = useState<number>();
  const [companyFormOpen, setCompanyFormOpen] = useState(false);
  const [editingCompany, setEditingCompany] = useState<CompanyResponse | null>(null);
  const [contactFormOpen, setContactFormOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [editingContact, setEditingContact] = useState<ContactResponse | null>(null);
  const [contactDefaults, setContactDefaults] = useState<Partial<ContactRequest>>();
  const [expanded, setExpanded] = useState<number[]>([]);
  // Ticked rows for the bulk add, kept across pages (see preserveSelectedRowKeys below).
  const [selectedIds, setSelectedIds] = useState<number[]>([]);

  // Edit mode is page-wide here: the rows are a list of contacts, so flipping it per row
  // would mean clicking Edit again for every person you touch.
  const [editMode, setEditMode] = useEditMode(undefined);

  const rows = query.data?.content ?? [];
  const term = (filters.contactValue ?? '').trim().toLowerCase();

  // Searching an address should land you on it, not make you hunt for the expander.
  useEffect(() => {
    if (term) setExpanded(rows.map((r) => r.person.id));
  }, [term, query.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const applyFilters = (values: Partial<PeopleFilter>) => {
    setFilters(values);
    tc.resetPage();
    setExpanded([]);
  };

  const openContactForm = (ct: ContactResponse | null, row?: PersonDetailResponse) => {
    setEditingContact(ct);
    setContactDefaults(
      ct ? undefined : { personId: row?.person.id, companyId: row?.person.companyId },
    );
    setContactFormOpen(true);
  };

  const columns: ColumnsType<PersonDetailResponse> = [
    {
      title: 'Full name',
      key: 'fullName',
      render: (_, r) => (
        <Space size={4} wrap>
          <Typography.Link onClick={() => setSelectedId(r.person.id)}>
            {r.person.fullName}
          </Typography.Link>
          {r.person.hasLeft && (
            <Tooltip title={`No longer at ${r.person.companyName ?? 'this company'}. Every address and number of theirs is out of circulations — collection and send time both.`}>
              <Tag color="red">left</Tag>
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      // Its own column rather than a line under the name: it is the field you scan a list
      // of people by when you are looking for whoever does chartering there.
      title: 'Job title',
      key: 'jobTitle',
      width: 180,
      render: (_, r) =>
        r.person.jobTitle ? (
          <Typography.Text>{r.person.jobTitle}</Typography.Text>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: 'Greeting',
      key: 'greeting',
      width: 180,
      render: (_, r) =>
        r.person.greetingName ? (
          <GreetingName title={r.person.title} name={r.person.greetingName} />
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: 'Company',
      key: 'company',
      render: (_, r) =>
        r.person.companyId != null ? (
          <Typography.Link onClick={() => setCompanyDrawerId(r.person.companyId!)}>
            {r.person.companyName}
          </Typography.Link>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: 'Contacts',
      key: 'contacts',
      width: 260,
      render: (_, r) => <ContactSummary contacts={r.contacts} />,
    },
    {
      /*
       * Edit alone. Delete and the left-the-company toggle moved into the edit form, where
       * the vessel and company ones already live: on a list of a hundred people they were a
       * hundred chances to take somebody off every circulation, or remove them outright,
       * from a screen you opened to read. Both are still one click further in, under a
       * button that says you mean to change this person.
       */
      title: 'Actions',
      key: 'actions',
      width: 100,
      render: (_, r) => (
        <Button size="small" onClick={() => { setEditing(r.person); setFormOpen(true); }}>
          Edit
        </Button>
      ),
    },
  ];

  return (
    <>
      {/* The Form wraps the panel rather than sitting inside it: on a phone the fields
          render into a drawer, which is a portal at the end of <body>, and only a Form
          above them in the React tree still reaches them there. */}
      <Form form={form} layout="vertical" onFinish={applyFilters}>
        <FilterPanel
          form={form}
          activeCount={countActiveFilters(filters)}
          onReset={() => { form.resetFields(); applyFilters({}); }}
          actions={
            <>
              <Button icon={<PlusOutlined />} onClick={() => { setEditing(null); setFormOpen(true); }}>
                New person
              </Button>
              {/* Contacts arrive as files far more often than one at a time — a list off a
                  trade fair, an export somebody mailed over. It sits beside New person
                  because it answers the same question, only in bulk. */}
              <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
                Import from file
              </Button>
              <AddToListActions
                entity="people"
                rowsArePeople
                selectedIds={selectedIds}
                totalMatching={query.data?.totalElements ?? 0}
                collect={(ids, confirmedOnly) => collectApi.fromPeople(filters, ids, confirmedOnly)}
                onCleared={() => setSelectedIds([])}
              />
              <EditToolbar editing={editMode} onToggle={setEditMode} />
            </>
          }
          extras={
            <>
              {/* Where the address came from, and it is a question about the address
                  rather than the person: an import that added one number to somebody
                  already on file brings them back under "Imported from a file", which is
                  what reviewing an import is looking for.

                  Three sources, not two. "Legacy (imported)" used to mean both doors at
                  once, and since the file importer landed it named neither clearly — a
                  file of eighty addresses is imported and is not legacy. */}
              <Form.Item name="source" noStyle initialValue="">
                <Select
                  style={{ width: 200 }}
                  options={[
                    { value: '', label: 'Source: all' },
                    { value: 'APP', label: 'Added in the app' },
                    { value: 'FILE', label: 'Imported from a file' },
                    { value: 'LEGACY', label: 'From the old database' },
                  ]}
                />
              </Form.Item>
              <Form.Item name="includeBanned" valuePropName="checked" noStyle>
                <Checkbox>Include banned (Russian-rooted)</Checkbox>
              </Form.Item>
            </>
          }
        >
          <Row gutter={12}>
            <Col xs={12} md={5}>
              <Form.Item name="name" label="Name" tooltip="Full name or greeting name">
                <Input allowClear placeholder="e.g. Sergey" />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item
                name="contactValue"
                label="Contact contains"
                tooltip="Find the person who owns an email address or phone number"
              >
                <Input allowClear placeholder="e.g. @kline.com" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="contactKind" label="Kind">
                <Select
                  allowClear
                  placeholder="Any"
                  options={[{ value: 'email', label: 'email' }, { value: 'phone', label: 'phone' }]}
                />
              </Form.Item>
            </Col>
            <Col xs={12} md={5}>
              <Form.Item name="companyId" label="Company">
                <CompanySelect allowClear placeholder="All companies" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="confirmed" label="Confirmed" initialValue="">
                <Select options={CONFIRMED_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>
        </FilterPanel>
      </Form>

      <ResponsiveTable<PersonDetailResponse>
        rowKey={(r) => r.person.id}
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={rows}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
        mobile={{
          // A link, the same one the desktop Name column carries. Companies and Vessels
          // open their drawer from a row click, which the card inherits for free; a person
          // row does not have one — it expands its contacts instead — so without this the
          // person's own record is a screen a phone simply cannot reach.
          title: (r) => (
            <Space size={4} wrap>
              <Typography.Link onClick={() => setSelectedId(r.person.id)}>
                {r.person.fullName}
              </Typography.Link>
              {r.person.hasLeft && <Tag color="red">left</Tag>}
            </Space>
          ),
          // The company is what tells two people with the same name apart, so it is the
          // line under the name rather than one box among the fields.
          subtitle: (r) => r.person.companyName ?? '—',
          fields: (r) => [
            r.person.jobTitle && { label: 'Job title', value: r.person.jobTitle },
            r.person.greetingName && {
              label: 'Greeting',
              value: <GreetingName title={r.person.title} name={r.person.greetingName} />,
            },
            { label: 'Contacts', value: <ContactSummary contacts={r.contacts} /> },
          ],
          // Same one button as the desktop column, for the same reason — and a card on a
          // phone has even less room to be careful in.
          actions: (r) => (
            <Button size="small" onClick={() => { setEditing(r.person); setFormOpen(true); }}>
              Edit
            </Button>
          ),
          // The addresses are the reason you opened a person, so the expander says how
          // many are behind it rather than a generic "Details".
          expandLabel: (r) =>
            `${r.contacts.length} contact${r.contacts.length === 1 ? '' : 's'}`,
        }}
        rowSelection={{
          selectedRowKeys: selectedIds,
          // Ticks on other pages are no longer in dataSource; without this, paging away
          // silently drops them from the selection.
          preserveSelectedRowKeys: true,
          onChange: (keys) => setSelectedIds(keys as number[]),
        }}
        expandable={{
          expandedRowKeys: expanded,
          onExpandedRowsChange: (keys) => setExpanded(keys as number[]),
          // Nothing to open for the 202 people with no contacts on file.
          rowExpandable: (r) => r.contacts.length > 0,
          expandedRowRender: (r) => (
            <>
              <List
                size="small"
                dataSource={r.contacts}
                renderItem={(ct) => (
                  <ContactLine
                    ct={ct}
                    showGreeting={false}
                    highlight={term || undefined}
                    editing={editMode}
                    onEdit={(target) => openContactForm(target)}
                    onDelete={(target) => removeContact.mutate(target.id)}
                  />
                )}
              />
              {editMode && (
                <Button
                  size="small"
                  icon={<PlusOutlined />}
                  style={{ marginTop: 4 }}
                  onClick={() => openContactForm(null, r)}
                >
                  Add contact
                </Button>
              )}
            </>
          ),
        }}
      />

      <PersonForm
        open={formOpen}
        editing={editing}
        onClose={() => setFormOpen(false)}
        onDeleted={() => setSelectedId(undefined)}
      />
      <PersonDrawer
        personId={selectedId}
        onClose={() => setSelectedId(undefined)}
        onEdit={(p) => { setEditing(p); setFormOpen(true); }}
        onOpenCompany={setCompanyDrawerId}
      />
      <CompanyDrawer
        companyId={companyDrawerId}
        initialTab="people"
        onClose={() => setCompanyDrawerId(undefined)}
        onEdit={(c) => { setEditingCompany(c); setCompanyFormOpen(true); }}
      />
      <CompanyForm
        open={companyFormOpen}
        editing={editingCompany}
        onClose={() => setCompanyFormOpen(false)}
        onDeleted={() => setCompanyDrawerId(undefined)}
      />
      <ContactForm
        open={contactFormOpen}
        editing={editingContact}
        defaults={contactDefaults}
        onClose={() => setContactFormOpen(false)}
      />
      <ImportContactsModal open={importOpen} onClose={() => setImportOpen(false)} />
    </>
  );
}

/** At-a-glance answer to "can I reach this person?" without expanding the row. */
function ContactSummary({ contacts }: { contacts: ContactResponse[] }) {
  const emails = contacts.filter((c) => c.contactKind === 'email');
  const phones = contacts.filter((c) => c.contactKind === 'phone');
  const noWorkingEmail = emails.length > 0 && emails.every((e) => !e.working);
  const main = contacts.find((c) => c.main);

  if (contacts.length === 0) return <Typography.Text type="secondary">none</Typography.Text>;

  return (
    <Space size={4} wrap>
      {emails.length > 0 && (
        <Tag icon={<MailOutlined />} color={noWorkingEmail ? 'red' : 'blue'}>
          {emails.length}
        </Tag>
      )}
      {phones.length > 0 && <Tag icon={<PhoneOutlined />}>{phones.length}</Tag>}
      {main && <Tag color="gold">main</Tag>}
      {noWorkingEmail && (
        <Tooltip title="Every email on file for this person is flagged not working">
          <Tag color="red">no working email</Tag>
        </Tooltip>
      )}
    </Space>
  );
}
