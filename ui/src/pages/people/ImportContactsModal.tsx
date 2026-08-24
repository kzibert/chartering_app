import { useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Divider,
  Empty,
  Input,
  Modal,
  Result,
  Row,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import {
  InboxOutlined,
  MailOutlined,
  PhoneOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import CompanySelect from '../../components/CompanySelect';
import {
  contactImportApi,
  type ContactImportPreview,
  type ContactImportRequest,
  type ContactImportResult,
  type ImportCompany,
  type ImportContact,
  type ImportPerson,
} from '../../api/contactImport';

interface Props {
  open: boolean;
  onClose: () => void;
}

/** A contact with a decision attached: is this one going in? */
type ContactState = ImportContact & { include: boolean };
type CompanyState = Omit<ImportCompany, 'contacts'> & { include: boolean; contacts: ContactState[] };
type PersonState = Omit<ImportPerson, 'contacts'> & { include: boolean; contacts: ContactState[] };

interface ReviewState {
  fileName?: string;
  fileWarnings: string[];
  counts: ContactImportPreview['counts'];
  companies: CompanyState[];
  people: PersonState[];
}

/**
 * Import a contacts export, in the two steps the data makes necessary.
 *
 * **Upload** sends the file to be parsed and matched; nothing is written. **Review** shows
 * what would happen, editable, and only then is anything saved. The review step is not
 * ceremony: a real export arrives with a company called by its own advertising slogan, a
 * website column holding an email address, and one mailbox listed against two managers. All
 * of it imports cleanly and all of it is wrong, and the only place to notice is a screen
 * that shows the result before it is a result.
 *
 * The screen is a card per company with its people nested inside, rather than one flat
 * table. Two reasons. Companies own the people, so unticking a company has to take its
 * people with it, and a nesting says that where two tables would have to explain it. And a
 * bad company name is a single mistake, not one per employee — fixing it once at the top of
 * the card is the whole difference between a usable review and a chore.
 */
export default function ImportContactsModal({ open, onClose }: Props) {
  const qc = useQueryClient();
  const [review, setReview] = useState<ReviewState | null>(null);
  const [done, setDone] = useState<ContactImportResult | null>(null);

  const preview = useMutation({
    mutationFn: (file: File) => contactImportApi.preview(file),
    onSuccess: (data) => setReview(toReviewState(data)),
  });

  const commit = useMutation({
    mutationFn: (body: ContactImportRequest) => contactImportApi.commit(body),
    onSuccess: (result) => {
      setDone(result);
      // Everything the import touched. 'companies' and 'people' are the two lists; 'company'
      // and 'contacts' are the drawers that may be open behind this modal; 'dashboard' is
      // where the counts live.
      ['companies', 'company', 'people', 'person', 'contacts', 'dashboard'].forEach((k) =>
        qc.invalidateQueries({ queryKey: [k] }),
      );
    },
  });

  const close = () => {
    onClose();
    // Cleared on the way out rather than on the way in, so the closing animation does not
    // play over a modal that has already emptied itself.
    setTimeout(() => {
      setReview(null);
      setDone(null);
      preview.reset();
      commit.reset();
    }, 200);
  };

  const patchCompany = (key: string, patch: Partial<CompanyState>) =>
    setReview((s) =>
      s ? { ...s, companies: s.companies.map((c) => (c.key === key ? { ...c, ...patch } : c)) } : s,
    );

  const patchPerson = (key: string, patch: Partial<PersonState>) =>
    setReview((s) =>
      s ? { ...s, people: s.people.map((p) => (p.key === key ? { ...p, ...patch } : p)) } : s,
    );

  const request = useMemo(() => (review ? toRequest(review) : null), [review]);

  const totals = useMemo(() => {
    if (!request) return { companies: 0, people: 0, contacts: 0 };
    const contacts =
      request.companies.reduce((n, c) => n + c.contacts.length, 0) +
      request.people.reduce((n, p) => n + p.contacts.length, 0);
    return { companies: request.companies.length, people: request.people.length, contacts };
  }, [request]);

  return (
    <Modal
      open={open}
      title="Import contacts from a file"
      width={900}
      onCancel={close}
      destroyOnClose
      footer={
        done ? (
          <Button type="primary" onClick={close}>
            Done
          </Button>
        ) : review ? (
          /* The commit button's label carries both counts, so this row is wider than a
             phone modal on its own. */
          <Space wrap>
            <Button onClick={() => setReview(null)}>Choose another file</Button>
            <Button
              type="primary"
              loading={commit.isPending}
              disabled={totals.companies === 0}
              onClick={() => request && commit.mutate(request)}
            >
              {`Import ${totals.people} ${totals.people === 1 ? 'person' : 'people'}`}
              {` and ${totals.contacts} ${totals.contacts === 1 ? 'contact' : 'contacts'}`}
            </Button>
          </Space>
        ) : (
          <Button onClick={close}>Cancel</Button>
        )
      }
    >
      {done ? (
        <ImportDone result={done} />
      ) : review ? (
        <Review
          state={review}
          totals={totals}
          onPatchCompany={patchCompany}
          onPatchPerson={patchPerson}
        />
      ) : (
        <UploadStep
          loading={preview.isPending}
          onFile={(file) => preview.mutate(file)}
        />
      )}
    </Modal>
  );
}

// ---- step 1: the file ------------------------------------------------------------

function UploadStep({ loading, onFile }: { loading: boolean; onFile: (file: File) => void }) {
  return (
    <Spin spinning={loading} tip="Reading the file…">
      <Upload.Dragger
        accept=".csv,text/csv"
        maxCount={1}
        showUploadList={false}
        // The file goes up through the same axios client as everything else, so it carries
        // the bearer token and lands in the same error handling. antd's own uploader would
        // do neither.
        beforeUpload={(file) => {
          onFile(file);
          return Upload.LIST_IGNORE;
        }}
        style={{ padding: 16 }}
      >
        <p className="ant-upload-drag-icon">
          <InboxOutlined />
        </p>
        <p className="ant-upload-text">Drop a contacts file here, or click to choose one</p>
        <p className="ant-upload-hint">
          A .csv export from Google Contacts, Outlook or a phone-book app. Nothing is saved
          yet — the next screen shows what the file would do, and you can change any of it
          before importing.
        </p>
      </Upload.Dragger>
    </Spin>
  );
}

// ---- step 2: the review ----------------------------------------------------------

function Review({
  state,
  totals,
  onPatchCompany,
  onPatchPerson,
}: {
  state: ReviewState;
  totals: { companies: number; people: number; contacts: number };
  onPatchCompany: (key: string, patch: Partial<CompanyState>) => void;
  onPatchPerson: (key: string, patch: Partial<PersonState>) => void;
}) {
  const { counts } = state;

  if (state.companies.length === 0) {
    return (
      <>
        {state.fileWarnings.map((w) => (
          <Alert key={w} type="warning" showIcon message={w} style={{ marginBottom: 8 }} />
        ))}
        <Empty description="Nothing in this file could be read as a company with contacts." />
      </>
    );
  }

  return (
    <div style={{ maxHeight: '65vh', overflowY: 'auto', paddingRight: 4 }}>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={`${state.fileName ?? 'This file'} — ${counts.companiesNew} new ${
          counts.companiesNew === 1 ? 'company' : 'companies'
        }, ${counts.companiesMatched} matched, ${counts.peopleNew} new ${
          counts.peopleNew === 1 ? 'person' : 'people'
        }, ${counts.emails} ${counts.emails === 1 ? 'email' : 'emails'}, ${counts.phones} ${
          counts.phones === 1 ? 'phone' : 'phones'
        }`}
        description={
          counts.duplicates > 0 || counts.warnings > 0 ? (
            <Space direction="vertical" size={2}>
              {counts.duplicates > 0 && (
                <Typography.Text>
                  {counts.duplicates} address{counts.duplicates === 1 ? '' : 'es'} already on
                  file — unticked below, tick one to store it anyway.
                </Typography.Text>
              )}
              {counts.warnings > 0 && (
                <Typography.Text>
                  {counts.warnings} thing{counts.warnings === 1 ? '' : 's'} worth a look
                  before importing.
                </Typography.Text>
              )}
            </Space>
          ) : undefined
        }
      />

      {state.fileWarnings.map((w) => (
        <Alert key={w} type="warning" showIcon message={w} style={{ marginBottom: 8 }} />
      ))}

      {state.companies.map((company) => (
        <CompanyCard
          key={company.key}
          company={company}
          people={state.people.filter((p) => p.companyKey === company.key)}
          onPatchCompany={onPatchCompany}
          onPatchPerson={onPatchPerson}
        />
      ))}

      {totals.companies === 0 && (
        <Alert
          type="warning"
          showIcon
          message="Nothing is ticked, so there is nothing to import."
        />
      )}
    </div>
  );
}

function CompanyCard({
  company,
  people,
  onPatchCompany,
  onPatchPerson,
}: {
  company: CompanyState;
  people: PersonState[];
  onPatchCompany: (key: string, patch: Partial<CompanyState>) => void;
  onPatchPerson: (key: string, patch: Partial<PersonState>) => void;
}) {
  const matched = company.matchedId != null;

  return (
    <Card
      size="small"
      style={{ marginBottom: 12, opacity: company.include ? 1 : 0.5 }}
      title={
        <Space wrap>
          <Checkbox
            checked={company.include}
            onChange={(e) => onPatchCompany(company.key, { include: e.target.checked })}
          />
          {matched ? (
            <Tag color={company.matchType === 'similar' ? 'orange' : 'green'}>
              {company.matchType === 'similar' ? 'looks like an existing company' : 'already on file'}
            </Tag>
          ) : (
            <Tag color="blue">new company</Tag>
          )}
          <Typography.Text type="secondary" style={{ fontWeight: 400, fontSize: 12 }}>
            {people.length} {people.length === 1 ? 'person' : 'people'}
          </Typography.Text>
        </Space>
      }
    >
      {company.warnings.map((w) => (
        <Alert
          key={w}
          type="warning"
          showIcon
          icon={<WarningOutlined />}
          message={w}
          style={{ marginBottom: 8 }}
        />
      ))}

      <Row gutter={[8, 8]}>
        <Col xs={24} md={12}>
          <FieldLabel
            text="Company name"
            hint={
              company.name.trim() !== company.sourceName
                ? `The file said "${company.sourceName}"`
                : undefined
            }
          />
          <Input
            value={company.name}
            disabled={!company.include || matched}
            onChange={(e) => onPatchCompany(company.key, { name: e.target.value })}
          />
        </Col>
        <Col xs={24} md={12}>
          <FieldLabel
            text="File under an existing company"
            hint="Leave empty to create a new one"
          />
          <CompanySelect
            allowClear
            value={company.matchedId}
            placeholder="Create a new company"
            onChange={(id) => onPatchCompany(company.key, { matchedId: id })}
          />
        </Col>
      </Row>

      {/* Only asked for a company being created. A company already on file keeps the
          details it has — the importer fills blanks but never overwrites, so showing these
          against a matched company would offer an edit that does not happen. */}
      {!matched && (
        <Row gutter={[8, 8]} style={{ marginTop: 8 }}>
          <Col xs={12} md={8}>
            <FieldLabel text="City" />
            <Input
              value={company.cityName ?? ''}
              disabled={!company.include}
              onChange={(e) => onPatchCompany(company.key, { cityName: e.target.value })}
            />
          </Col>
          <Col xs={12} md={8}>
            <FieldLabel text="Country" />
            <Input
              value={company.country ?? ''}
              disabled={!company.include}
              onChange={(e) => onPatchCompany(company.key, { country: e.target.value })}
            />
          </Col>
          <Col xs={24} md={8}>
            <FieldLabel text="Website" />
            <Input
              value={company.website ?? ''}
              disabled={!company.include}
              onChange={(e) => onPatchCompany(company.key, { website: e.target.value })}
            />
          </Col>
        </Row>
      )}

      {company.contacts.length > 0 && (
        <>
          <Divider orientation="left" plain style={{ margin: '12px 0 8px' }}>
            <Tooltip title="Addresses that belong to the company itself rather than to any one person — a chartering@ or ops@ desk. Circulations treat these as a group of their own.">
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                Company-wide addresses
              </Typography.Text>
            </Tooltip>
          </Divider>
          <ContactList
            contacts={company.contacts}
            disabled={!company.include}
            onToggle={(index, include) =>
              onPatchCompany(company.key, {
                contacts: company.contacts.map((c, i) => (i === index ? { ...c, include } : c)),
              })
            }
          />
        </>
      )}

      {people.length > 0 && (
        <>
          <Divider orientation="left" plain style={{ margin: '12px 0 8px' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              People
            </Typography.Text>
          </Divider>
          {people.map((person) => (
            <PersonBlock
              key={person.key}
              person={person}
              // Unticking a company takes its people with it: they have nowhere to be
              // imported to, and the server refuses a person whose company is not in the
              // request rather than filing them under none.
              companyIncluded={company.include}
              onPatch={onPatchPerson}
            />
          ))}
        </>
      )}
    </Card>
  );
}

function PersonBlock({
  person,
  companyIncluded,
  onPatch,
}: {
  person: PersonState;
  companyIncluded: boolean;
  onPatch: (key: string, patch: Partial<PersonState>) => void;
}) {
  const disabled = !companyIncluded || !person.include;
  const matched = person.matchedId != null;

  return (
    <div
      style={{
        borderLeft: '2px solid rgba(5,5,5,0.06)',
        paddingLeft: 12,
        marginBottom: 12,
        opacity: disabled ? 0.5 : 1,
      }}
    >
      <Space wrap style={{ marginBottom: 6 }}>
        <Checkbox
          checked={person.include && companyIncluded}
          disabled={!companyIncluded}
          onChange={(e) => onPatch(person.key, { include: e.target.checked })}
        />
        {matched && (
          <Tooltip title="Somebody of this name is already at this company. Their addresses below will be added to that record rather than to a second one.">
            <Tag color="green">already on file</Tag>
          </Tooltip>
        )}
      </Space>

      {person.warnings.map((w) => (
        <Alert key={w} type="warning" showIcon message={w} style={{ marginBottom: 6 }} />
      ))}

      <Row gutter={[8, 8]}>
        <Col xs={24} md={9}>
          <FieldLabel text="Full name" />
          <Input
            value={person.fullName}
            disabled={disabled || matched}
            onChange={(e) => onPatch(person.key, { fullName: e.target.value })}
          />
        </Col>
        <Col xs={24} md={9}>
          <FieldLabel text="Job title" />
          <Input
            value={person.jobTitle ?? ''}
            disabled={disabled}
            maxLength={120}
            onChange={(e) => onPatch(person.key, { jobTitle: e.target.value })}
          />
        </Col>
        <Col xs={24} md={6}>
          <FieldLabel
            text="Greeting"
            hint="What a circular opens with"
          />
          <Input
            value={person.greetingName ?? ''}
            disabled={disabled}
            maxLength={120}
            onChange={(e) => onPatch(person.key, { greetingName: e.target.value })}
          />
        </Col>
      </Row>

      <div style={{ marginTop: 6 }}>
        {person.contacts.length > 0 ? (
          <ContactList
            contacts={person.contacts}
            disabled={disabled}
            onToggle={(index, include) =>
              onPatch(person.key, {
                contacts: person.contacts.map((c, i) => (i === index ? { ...c, include } : c)),
              })
            }
          />
        ) : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            No email or phone on this row.
          </Typography.Text>
        )}
      </div>
    </div>
  );
}

function ContactList({
  contacts,
  disabled,
  onToggle,
}: {
  contacts: ContactState[];
  disabled: boolean;
  onToggle: (index: number, include: boolean) => void;
}) {
  return (
    <Space direction="vertical" size={2} style={{ width: '100%' }}>
      {contacts.map((contact, index) => (
        <Space key={`${contact.kind}-${contact.value}`} size={6} wrap>
          <Checkbox
            checked={contact.include}
            disabled={disabled}
            onChange={(e) => onToggle(index, e.target.checked)}
          />
          {contact.kind === 'email' ? <MailOutlined /> : <PhoneOutlined />}
          <Typography.Text delete={!contact.include} style={{ fontSize: 13 }}>
            {contact.value}
          </Typography.Text>
          {contact.label && <Tag style={{ marginInlineEnd: 0 }}>{contact.label}</Tag>}
          {contact.warning && (
            <Tooltip title={contact.warning}>
              <Tag color={contact.duplicate ? 'default' : 'orange'} style={{ marginInlineEnd: 0 }}>
                {contact.duplicate ? 'already on file' : 'company-wide'}
              </Tag>
            </Tooltip>
          )}
        </Space>
      ))}
    </Space>
  );
}

// ---- step 3: what happened -------------------------------------------------------

function ImportDone({ result }: { result: ContactImportResult }) {
  return (
    <Result
      status="success"
      title="Imported"
      subTitle={
        <Space direction="vertical" size={2}>
          <Typography.Text>
            {result.companiesCreated} {result.companiesCreated === 1 ? 'company' : 'companies'}{' '}
            created, {result.companiesMatched} matched to one already on file.
          </Typography.Text>
          <Typography.Text>
            {result.peopleCreated} {result.peopleCreated === 1 ? 'person' : 'people'} created,{' '}
            {result.peopleMatched} matched.
          </Typography.Text>
          <Typography.Text>
            {result.contactsCreated} address
            {result.contactsCreated === 1 ? '' : 'es'} added
            {result.contactsSkipped > 0
              ? `, ${result.contactsSkipped} skipped as already on file.`
              : '.'}
          </Typography.Text>
          {/* Everything lands unconfirmed and unflagged. Saying so here is the difference
              between "why is my circular not picking these up" and knowing where to go. */}
          <Typography.Text type="secondary">
            Nothing imported is confirmed or flagged for circulations yet — set those on the
            People tab once you have looked at the rows.
          </Typography.Text>
        </Space>
      }
    />
  );
}

// ---- plumbing --------------------------------------------------------------------

function FieldLabel({ text, hint }: { text: string; hint?: string }) {
  return (
    <div style={{ fontSize: 12, marginBottom: 2 }}>
      <Typography.Text type="secondary">{text}</Typography.Text>
      {hint && (
        <Typography.Text type="secondary" style={{ marginLeft: 6, fontStyle: 'italic' }}>
          {hint}
        </Typography.Text>
      )}
    </div>
  );
}

function toReviewState(preview: ContactImportPreview): ReviewState {
  // Everything is ticked except addresses the company already has. Those default off
  // because storing a second copy is the rarer intention by far — but they stay on screen
  // and tickable, since a company can legitimately be given the same address twice under
  // two different people.
  const withDecisions = (contacts: ImportContact[]): ContactState[] =>
    contacts.map((c) => ({ ...c, include: !c.duplicate }));

  return {
    fileName: preview.fileName,
    fileWarnings: preview.fileWarnings ?? [],
    counts: preview.counts,
    companies: preview.companies.map((c) => ({
      ...c,
      include: true,
      contacts: withDecisions(c.contacts ?? []),
    })),
    people: preview.people.map((p) => ({
      ...p,
      include: true,
      contacts: withDecisions(p.contacts ?? []),
    })),
  };
}

function toRequest(state: ReviewState): ContactImportRequest {
  const companies = state.companies.filter((c) => c.include);
  const keys = new Set(companies.map((c) => c.key));

  const contacts = (list: ContactState[]) =>
    list
      .filter((c) => c.include)
      .map((c) => ({ kind: c.kind, value: c.value, label: c.label }));

  return {
    companies: companies.map((c) => ({
      key: c.key,
      name: c.name.trim(),
      matchedId: c.matchedId,
      cityName: blankToUndefined(c.cityName),
      country: blankToUndefined(c.country),
      website: blankToUndefined(c.website),
      notes: c.notes,
      contacts: contacts(c.contacts),
    })),
    people: state.people
      // A person whose company was unticked is dropped with it, not imported company-less.
      .filter((p) => p.include && keys.has(p.companyKey))
      .map((p) => ({
        key: p.key,
        companyKey: p.companyKey,
        fullName: p.fullName.trim(),
        title: blankToUndefined(p.title),
        jobTitle: blankToUndefined(p.jobTitle),
        greetingName: blankToUndefined(p.greetingName),
        matchedId: p.matchedId,
        notes: p.notes,
        contacts: contacts(p.contacts),
      })),
  };
}

function blankToUndefined(s?: string) {
  return s == null || s.trim() === '' ? undefined : s.trim();
}
