import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Alert,
  Button,
  Card,
  Checkbox,
  Col,
  Form,
  Input,
  Radio,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { CheckCircleOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import CompanyDrawer from '../companies/CompanyDrawer';
import { intakeApi } from '../../api/intake';
import type {
  IntakePasteCompanyComparison,
  IntakePasteCompanyRequest,
  IntakePasteCompanyResponse,
  PasteCompanyChanges,
  PasteCompanyDraft,
  PasteContactDraft,
  PastePersonDraft,
} from '../../api/intake';
import type { CompanyRequest } from '../../api/types';

/**
 * The company block — one card, two screens.
 *
 * <b>Lifted out of the paste modal when the review queue grew a question of its own.</b> A
 * signature read off a board or out of a circular asks exactly what a pasted one asks — which
 * firm is this, what does the record say, what should change — and the answer is allowed to
 * write exactly the same things. Two renderings of that would be two ideas of which parts of a
 * signature are safe to overwrite, and the one nobody was looking at would be the one that
 * quietly replaced a city somebody had typed.
 *
 * What the two screens differ in is where the accept goes, which is the {@link CompanyCardProps#save}
 * prop and nothing else: the paste modal posts a free-standing signature, the queue posts one
 * against an item it then marks answered.
 */

export function DoneTag({ note }: { note?: string }) {
  return note ? (
    <Tag icon={<CheckCircleOutlined />} color="success">
      {note}
    </Tag>
  ) : null;
}

const NEW = 'new';
const PHONE_LABELS = ['Work', 'Mobile', 'Direct', 'Fax'].map((l) => ({ value: l, label: l }));
const MATCH_COLOUR: Record<string, string> = {
  email: 'green',
  name: 'green',
  phone: 'green',
  domain: 'gold',
  similar: 'default',
};

/** A person as the card edits it: the draft, plus what the comparison said about them. */
type PersonRow = PastePersonDraft & { matchedBy?: 'name' | 'surname' };

/**
 * A contact as the card edits it. `onFileId` is the contact the company already has with this
 * value; such a row adds nothing, and changes the one on file only when `update` is ticked.
 */
type ContactRow = PasteContactDraft & {
  onFileId?: number;
  currentLabel?: string;
  currentPersonName?: string;
  update?: boolean;
};

/**
 * The company block: which firm this is, and what to do to it.
 *
 * **Against a company on file, nothing changes unless it is ticked.** The text is a signature,
 * not a source of record, so picking a match compares the two and shows the differences as
 * choices: each company field that differs, each person set against the person on file they
 * appear to be, each address or number marked new or already there. Only the ticked
 * differences and the new rows are sent. A new company is simply the whole draft, editable.
 *
 * A match is pre-picked only when it is a strong one — the same address, name or phone — and
 * even then it is only the starting position of a radio button.
 */
export interface CompanyCardProps {
  draft: PasteCompanyDraft;
  /** Set once it has been saved: the card locks and says what it did. */
  doneNote?: string;
  onDone: (note: string) => void;
  /**
   * Where the accept goes.
   *
   * <p>The one thing the two callers differ in. The paste modal posts a free-standing
   * signature; the review queue posts the same body against the item it came from, which is
   * then marked answered. Everything above it — which firm, what differs, what to tick — is
   * the same decision and is therefore the same code.
   */
  save?: (body: IntakePasteCompanyRequest) => Promise<IntakePasteCompanyResponse>;
  /** What the primary button says when a firm on file is picked. */
  saveLabel?: string;
  /** Rendered as a plain block rather than a titled card, for a drawer that has its own. */
  bare?: boolean;
}

export default function CompanyCard({
  draft,
  doneNote,
  onDone,
  save = intakeApi.pasteCompany,
  saveLabel,
  bare = false,
}: CompanyCardProps) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [form] = Form.useForm<CompanyRequest>();
  const strong = draft.matches.find(
    (m) => m.how === 'email' || m.how === 'name' || m.how === 'phone',
  );
  const [target, setTarget] = useState<number | typeof NEW>(strong ? strong.companyId : NEW);
  const [people, setPeople] = useState<PersonRow[]>(draft.people);
  const [contacts, setContacts] = useState<ContactRow[]>(draft.contacts);
  const [comparison, setComparison] = useState<IntakePasteCompanyComparison | null>(null);
  const [fieldTicks, setFieldTicks] = useState<Record<string, boolean>>({});
  // Whose record the drawer over this one is showing. A match is a name and a city, which is
  // not enough to tell two firms of the same family apart — the record behind it is, so every
  // name here opens it, before the save and after.
  const [viewing, setViewing] = useState<number>();
  /** The company this card wrote to, so the result can be opened rather than hunted for. */
  const [saved, setSaved] = useState<{ id: number; name: string }>();
  const locked = !!doneNote;

  const compare = useMutation({
    mutationFn: (companyId: number) =>
      intakeApi.comparePastedCompany({
        companyId,
        company: draft.company,
        people: people.map(({ fullName, title, jobTitle }) => ({ fullName, title, jobTitle })),
        contacts: contacts.map(({ kind, value, label, personName }) => ({ kind, value, label, personName })),
      }),
    onSuccess: (c) => {
      setComparison(c);
      // Every differing field starts unticked except where the record is empty: filling a
      // blank is not overwriting anybody's work, so it is the one change worth assuming.
      setFieldTicks(Object.fromEntries(c.fields.map((f) => [f.field, !f.current?.trim()])));
      setPeople((rows) =>
        rows.map((p, i) => ({
          ...p,
          existingPersonId: c.people[i]?.existingPersonId,
          matchedBy: c.people[i]?.matchedBy,
        })),
      );
      setContacts((rows) =>
        rows.map((row, i) => {
          const hit = c.contacts[i];
          return {
            ...row,
            onFileId: hit?.existingContactId,
            currentLabel: hit?.currentLabel,
            currentPersonName: hit?.currentPersonName,
            update: false,
          };
        }),
      );
    },
  });

  // Compared whenever a company on file is picked; forgotten when "new" is, because a new
  // company has nothing on file to set the rows against.
  useEffect(() => {
    if (target === NEW) {
      setComparison(null);
      setPeople((rows) => rows.map((p) => ({ ...p, existingPersonId: undefined, matchedBy: undefined })));
      setContacts((rows) => rows.map((c) => ({ ...c, onFileId: undefined, update: false })));
    } else {
      compare.mutate(target);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- rerun on the target alone
  }, [target]);

  const accept = useMutation({
    mutationFn: async () => {
      const onFile = target !== NEW;
      const company = onFile ? undefined : await form.validateFields();
      const companyChanges: PasteCompanyChanges | undefined =
        onFile && comparison
          ? Object.fromEntries(
              comparison.fields.filter((f) => fieldTicks[f.field]).map((f) => [f.field, f.parsed]),
            )
          : undefined;
      return save({
        companyId: onFile ? target : undefined,
        company,
        companyChanges,
        people: people
          .filter((p) => p.fullName?.trim())
          .map(({ fullName, title, jobTitle, existingPersonId }) => ({
            fullName,
            title,
            jobTitle,
            existingPersonId: onFile ? existingPersonId : undefined,
          })),
        contacts: contacts
          .filter((c) => c.value?.trim())
          // Already on the company and not ticked for an update: nothing to send.
          .filter((c) => !(onFile && c.onFileId && !c.update))
          .map(({ kind, value, label, personName, onFileId, update }) => ({
            kind,
            value,
            label,
            personName,
            existingContactId: onFile && onFileId && update ? onFileId : undefined,
          })),
      });
    },
    onSuccess: (r) => {
      const parts = [
        r.companyFieldsUpdated ? `${r.companyFieldsUpdated} field${r.companyFieldsUpdated === 1 ? '' : 's'} updated` : null,
        r.peopleAdded ? `${r.peopleAdded} ${r.peopleAdded === 1 ? 'person' : 'people'} added` : null,
        r.peopleUpdated ? `${r.peopleUpdated} updated` : null,
        r.contactsAdded ? `${r.contactsAdded} contact${r.contactsAdded === 1 ? '' : 's'} added` : null,
        r.contactsUpdated ? `${r.contactsUpdated} contact${r.contactsUpdated === 1 ? '' : 's'} updated` : null,
      ].filter(Boolean);
      const skipped = r.skipped?.length ? ` · already on file: ${r.skipped.join(', ')}` : '';
      message.success(
        `${r.created ? 'Created' : 'Saved to'} ${r.companyName}: ${parts.join(', ') || 'nothing to change'}${skipped}`,
      );
      setSaved({ id: r.companyId, name: r.companyName });
      onDone(`${r.created ? 'Created' : 'Saved to'} ${r.companyName}`);
      ['companies', 'company', 'people', 'person', 'contacts', 'dashboard'].forEach((k) =>
        qc.invalidateQueries({ queryKey: [k] }),
      );
    },
  });

  const onFileCompany = target !== NEW;
  const peopleOnFile = comparison?.peopleOnFile ?? [];

  const personOptions = useMemo(() => {
    const names = new Set<string>();
    people.forEach((p) => p.fullName?.trim() && names.add(p.fullName.trim()));
    peopleOnFile.forEach((p) => names.add(p.fullName));
    return [
      { value: '', label: 'Company-wide' },
      ...[...names].map((n) => ({ value: n, label: n })),
    ];
  }, [people, peopleOnFile]);

  const setPerson = (i: number, patch: Partial<PersonRow>) =>
    setPeople((list) => list.map((p, j) => (j === i ? { ...p, ...patch } : p)));
  const setContact = (i: number, patch: Partial<ContactRow>) =>
    setContacts((list) => list.map((c, j) => (j === i ? { ...c, ...patch } : c)));

  const contactChanged = (c: ContactRow) =>
    (c.kind === 'phone' && (c.label ?? '') !== (c.currentLabel ?? '')) ||
    ((c.personName ?? '') !== '' && (c.personName ?? '') !== (c.currentPersonName ?? ''));

  // A drawer that already names what it is about does not want a second heading inside it,
  // and a card inside a card is a border for its own sake. The contents are identical.
  const Frame = bare ? Bare : Card;

  return (
    <Frame size="small" title={<Space>Company<DoneTag note={doneNote} /></Space>}>
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Typography.Text strong>Which company is this?</Typography.Text>
          <Radio.Group
            style={{ display: 'block', marginTop: 8 }}
            value={target}
            onChange={(e) => setTarget(e.target.value)}
            disabled={locked}
          >
            <Space direction="vertical" size={4}>
              {draft.matches.map((m) => (
                <Radio key={m.companyId} value={m.companyId}>
                  <Space size={4} wrap>
                    <span>
                      {/* Inside a Radio's own label, so a plain click would pick the firm as
                          well as open it. preventDefault stops the label activating its
                          input; the radio keeps whatever it had, which is the point — this
                          is the click that answers "is that the same ACME?". */}
                      <Typography.Link
                        style={{ cursor: 'pointer' }}
                        onClick={(e) => {
                          e.preventDefault();
                          e.stopPropagation();
                          setViewing(m.companyId);
                        }}
                      >
                        {m.name}
                      </Typography.Link>
                      {m.city || m.country ? (
                        <Typography.Text type="secondary">
                          {' '}— {[m.city, m.country].filter(Boolean).join(', ')}
                        </Typography.Text>
                      ) : null}
                    </span>
                    {m.reasons.map((r) => (
                      <Tag key={r} color={MATCH_COLOUR[m.how]} style={{ marginInlineEnd: 0 }}>
                        {r}
                      </Tag>
                    ))}
                  </Space>
                </Radio>
              ))}
              <Radio value={NEW}>
                {draft.matches.length
                  ? 'None of these — create a new company'
                  : 'Not on file — create a new company'}
              </Radio>
            </Space>
          </Radio.Group>
        </div>

        {onFileCompany && compare.isPending && <Spin tip="Comparing with the record…" />}

        {/* ---- the company itself ---- */}
        {!onFileCompany && (
          <Form form={form} layout="vertical" initialValues={draft.company} disabled={locked}>
            <Row gutter={12}>
              <Col xs={24} md={12}>
                <Form.Item name="name" label="Name" rules={[{ required: true, message: 'name is required' }]}>
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item name="cityName" label="City">
                  <Input />
                </Form.Item>
              </Col>
              <Col xs={12} md={6}>
                <Form.Item name="country" label="Country">
                  <Input maxLength={100} />
                </Form.Item>
              </Col>
            </Row>
            <Row gutter={12}>
              <Col xs={24} md={12}>
                <Form.Item name="website" label="Website">
                  <Input maxLength={255} />
                </Form.Item>
              </Col>
              <Col xs={24} md={12}>
                <Form.Item label="Roles">
                  <Space wrap>
                    <Form.Item name="shipowner" valuePropName="checked" noStyle><Checkbox>Owner</Checkbox></Form.Item>
                    <Form.Item name="charterer" valuePropName="checked" noStyle><Checkbox>Charterer</Checkbox></Form.Item>
                    <Form.Item name="broker" valuePropName="checked" noStyle><Checkbox>Broker</Checkbox></Form.Item>
                    <Form.Item name="agent" valuePropName="checked" noStyle><Checkbox>Agent</Checkbox></Form.Item>
                  </Space>
                </Form.Item>
              </Col>
            </Row>
            <Form.Item name="notes" label="Notes" tooltip="The address goes here — there is no column for one.">
              <Input.TextArea rows={2} />
            </Form.Item>
          </Form>
        )}

        {onFileCompany && comparison && (
          <div>
            <Typography.Text strong>
              What the text says about{' '}
              <Typography.Link onClick={() => setViewing(comparison.companyId)}>
                {comparison.companyName}
              </Typography.Link>
            </Typography.Text>
            {comparison.fields.length === 0 ? (
              <Typography.Paragraph type="secondary" style={{ marginTop: 4, marginBottom: 0 }}>
                Nothing the record does not already say.
              </Typography.Paragraph>
            ) : (
              <Space direction="vertical" size={4} style={{ width: '100%', marginTop: 8 }}>
                {comparison.fields.map((f) => (
                  <Checkbox
                    key={f.field}
                    checked={!!fieldTicks[f.field]}
                    disabled={locked}
                    onChange={(e) => setFieldTicks((t) => ({ ...t, [f.field]: e.target.checked }))}
                  >
                    <Space size={4} wrap>
                      <span>{f.field === 'notes' ? 'Add to notes' : `Update ${f.label.toLowerCase()}`}:</span>
                      {f.current?.trim() && f.field !== 'notes' ? (
                        <>
                          <Typography.Text delete type="secondary">{f.current}</Typography.Text>
                          <span>→</span>
                        </>
                      ) : null}
                      <Typography.Text strong>{f.parsed}</Typography.Text>
                      {!f.current?.trim() && <Tag>empty on file</Tag>}
                    </Space>
                  </Checkbox>
                ))}
              </Space>
            )}
          </div>
        )}

        {/* ---- people ---- */}
        <div>
          <Typography.Text strong>People</Typography.Text>
          <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 8 }}>
            {people.length === 0 && <Typography.Text type="secondary">None read.</Typography.Text>}
            {people.map((p, i) => {
              const onFile = peopleOnFile.find((x) => x.personId === p.existingPersonId);
              return (
                <div key={i}>
                  <Row gutter={8} align="middle">
                    <Col xs={4} md={3}>
                      <Input placeholder="Mr." value={p.title} disabled={locked}
                        onChange={(e) => setPerson(i, { title: e.target.value })} />
                    </Col>
                    <Col xs={10} md={7}>
                      <Input placeholder="Full name" value={p.fullName} disabled={locked}
                        onChange={(e) => setPerson(i, { fullName: e.target.value })} />
                    </Col>
                    <Col xs={8} md={6}>
                      <Input placeholder="Job title" value={p.jobTitle} disabled={locked}
                        onChange={(e) => setPerson(i, { jobTitle: e.target.value })} />
                    </Col>
                    {onFileCompany && (
                      <Col xs={20} md={6}>
                        <Select
                          style={{ width: '100%' }}
                          disabled={locked || !comparison}
                          value={p.existingPersonId ?? 0}
                          onChange={(id) => setPerson(i, { existingPersonId: id || undefined, matchedBy: undefined })}
                          options={[
                            { value: 0, label: 'New person' },
                            ...peopleOnFile.map((x) => ({ value: x.personId, label: `Is ${x.fullName}` })),
                          ]}
                        />
                      </Col>
                    )}
                    <Col xs={2}>
                      <Button type="text" danger icon={<DeleteOutlined />} aria-label="Leave this person out"
                        disabled={locked} onClick={() => setPeople((list) => list.filter((_, j) => j !== i))} />
                    </Col>
                  </Row>
                  {onFile && (
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {p.matchedBy === 'surname' ? 'Same surname and initial — check it is them. ' : ''}
                      On file as {[onFile.title, onFile.fullName].filter(Boolean).join(' ')}
                      {onFile.jobTitle ? `, ${onFile.jobTitle}` : ''}. Saving updates them to the
                      name and titles above; a blank box leaves that one as it is.
                    </Typography.Text>
                  )}
                </div>
              );
            })}
            <Button size="small" icon={<PlusOutlined />} disabled={locked}
              onClick={() => setPeople((list) => [...list, { fullName: '' }])}>
              Add a person
            </Button>
          </Space>
        </div>

        {/* ---- contacts ---- */}
        <div>
          <Typography.Text strong>Emails and phones</Typography.Text>
          <Space direction="vertical" size={8} style={{ width: '100%', marginTop: 8 }}>
            {contacts.length === 0 && <Typography.Text type="secondary">None read.</Typography.Text>}
            {contacts.map((c, i) => (
              <div key={i}>
                <Row gutter={8} align="middle">
                  <Col xs={6} md={3}>
                    <Select value={c.kind} style={{ width: '100%' }} disabled={locked || !!c.onFileId}
                      options={[{ value: 'email', label: 'Email' }, { value: 'phone', label: 'Phone' }]}
                      onChange={(kind) => setContact(i, { kind, label: kind === 'phone' ? c.label ?? 'Work' : undefined })} />
                  </Col>
                  <Col xs={18} md={8}>
                    <Input value={c.value} disabled={locked || !!c.onFileId}
                      onChange={(e) => setContact(i, { value: e.target.value })} />
                  </Col>
                  <Col xs={8} md={4}>
                    <Select value={c.kind === 'phone' ? c.label : undefined} placeholder="—" style={{ width: '100%' }}
                      disabled={c.kind !== 'phone' || locked} options={PHONE_LABELS}
                      onChange={(label) => setContact(i, { label })} />
                  </Col>
                  <Col xs={14} md={6}>
                    <Select value={c.personName ?? ''} style={{ width: '100%' }} options={personOptions} disabled={locked}
                      onChange={(personName) => setContact(i, { personName: personName || undefined })} />
                  </Col>
                  <Col xs={2}>
                    <Button type="text" danger icon={<DeleteOutlined />} aria-label="Leave this out" disabled={locked}
                      onClick={() => setContacts((list) => list.filter((_, j) => j !== i))} />
                  </Col>
                </Row>
                {onFileCompany && comparison && (
                  <div style={{ marginTop: 2 }}>
                    {c.onFileId ? (
                      contactChanged(c) ? (
                        <Checkbox checked={!!c.update} disabled={locked}
                          onChange={(e) => setContact(i, { update: e.target.checked })}>
                          <Typography.Text style={{ fontSize: 12 }}>
                            Already on file
                            {c.currentLabel ? ` as ${c.currentLabel}` : ''}
                            {c.currentPersonName ? `, ${c.currentPersonName}'s` : ', company-wide'} — update it to the above
                          </Typography.Text>
                        </Checkbox>
                      ) : (
                        <Tag style={{ fontSize: 11 }}>Already on file — nothing to add</Tag>
                      )
                    ) : (
                      c.value?.trim() && <Tag color="blue" style={{ fontSize: 11 }}>New — will be added</Tag>
                    )}
                  </div>
                )}
              </div>
            ))}
            <Button size="small" icon={<PlusOutlined />} disabled={locked}
              onClick={() => setContacts((list) => [...list, { kind: 'email', value: '' }])}>
              Add an email or phone
            </Button>
          </Space>
        </div>

        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {onFileCompany
            ? 'Adds the new people and contacts to the company on file and changes only what is ticked or matched above. '
            : 'Creates the company with these people and contacts. '}
          Nothing is flagged main or for circulation.
        </Typography.Text>
        {saved ? (
          <Button onClick={() => setViewing(saved.id)}>Open {saved.name}</Button>
        ) : (
          <Button
            type="primary"
            loading={accept.isPending}
            disabled={locked || (onFileCompany && !comparison)}
            onClick={() => accept.mutate()}
          >
            {onFileCompany ? (saveLabel ?? 'Save to this company') : 'Create the company'}
          </Button>
        )}
      </Space>

      {/* Read-only, like the one the review queue opens and for the same reason: this dialog
          is open to decide what the text says about a firm, and editing that firm underneath
          it would be two half-finished jobs at once. Omitting onEdit hides the Edit button
          rather than leaving a dead one. */}
      <CompanyDrawer companyId={viewing} onClose={() => setViewing(undefined)} />
    </Frame>
  );
}

/** The card's contents with no border and no title — see `bare`. */
function Bare({ children }: { size?: string; title?: unknown; children?: React.ReactNode }) {
  return <>{children}</>;
}
