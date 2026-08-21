import { useEffect, useMemo, useState } from 'react';
import {
  Button,
  Card,
  Checkbox,
  Col,
  Form,
  Input,
  List,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { MailOutlined, PhoneOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { usePeopleSearch, usePersonMutations, useContactMutations } from '../../api/hooks';
import { useTableControls } from '../../components/useTableControls';
import { usePersistedFilters } from '../../components/usePersistedState';
import { CONFIRMED_OPTIONS } from '../../components/filterOptions';
import CompanySelect from '../../components/CompanySelect';
import ContactLine from '../../components/ContactLine';
import AddToListActions from '../../components/AddToListActions';
import { collectApi } from '../../api/circulations';
import EditToolbar, { useEditMode } from '../../components/EditToolbar';
import GreetingName from '../../components/GreetingName';
import LeftCompanyButton from '../../components/LeftCompanyButton';
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
  const { remove } = usePersonMutations();
  const { remove: removeContact } = useContactMutations();

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<PersonResponse | null>(null);
  const [selectedId, setSelectedId] = useState<number>();
  const [companyDrawerId, setCompanyDrawerId] = useState<number>();
  const [companyFormOpen, setCompanyFormOpen] = useState(false);
  const [editingCompany, setEditingCompany] = useState<CompanyResponse | null>(null);
  const [contactFormOpen, setContactFormOpen] = useState(false);
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
      title: 'Actions',
      key: 'actions',
      width: 260,
      render: (_, r) => (
        <Space size={4}>
          <Button size="small" onClick={() => { setEditing(r.person); setFormOpen(true); }}>
            Edit
          </Button>
          <LeftCompanyButton p={r.person} />
          <Popconfirm title="Delete this person?" onConfirm={() => remove.mutate(r.person.id)}>
            <Button size="small" danger>Delete</Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <>
      <Card size="small" style={{ marginBottom: 16 }}>
        <Form form={form} layout="vertical" onFinish={applyFilters}>
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
          <Space wrap>
            <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>Search</Button>
            <Button onClick={() => { form.resetFields(); applyFilters({}); }}>Reset</Button>
            <Button icon={<PlusOutlined />} onClick={() => { setEditing(null); setFormOpen(true); }}>
              New person
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
            <Form.Item name="legacy" noStyle initialValue="">
              <Select
                style={{ width: 180 }}
                options={[
                  { value: '', label: 'Source: all' },
                  { value: false, label: 'New (app)' },
                  { value: true, label: 'Legacy (imported)' },
                ]}
              />
            </Form.Item>
            <Form.Item name="includeBanned" valuePropName="checked" noStyle>
              <Checkbox>Include banned (Russian-rooted)</Checkbox>
            </Form.Item>
          </Space>
        </Form>
      </Card>

      <Table<PersonDetailResponse>
        rowKey={(r) => r.person.id}
        size="small"
        loading={query.isLoading}
        columns={columns}
        dataSource={rows}
        pagination={tc.pagination(query.data?.totalElements ?? 0)}
        onChange={tc.onChange}
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

      <PersonForm open={formOpen} editing={editing} onClose={() => setFormOpen(false)} />
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
      <CompanyForm open={companyFormOpen} editing={editingCompany} onClose={() => setCompanyFormOpen(false)} />
      <ContactForm
        open={contactFormOpen}
        editing={editingContact}
        defaults={contactDefaults}
        onClose={() => setContactFormOpen(false)}
      />
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
