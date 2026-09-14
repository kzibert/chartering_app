import { useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Col,
  Empty,
  Form,
  Input,
  Modal,
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
import CargoForm from '../cargoes/CargoForm';
import PositionForm from '../openFleet/PositionForm';
import VesselForm from '../vessels/VesselForm';
import { intakeApi } from '../../api/intake';
import type {
  IntakePasteCompanyComparison,
  IntakePasteDraft,
  PasteCargoDraft,
  PasteCompanyChanges,
  PasteCompanyDraft,
  PasteContactDraft,
  PastePersonDraft,
  PasteVesselDraft,
} from '../../api/intake';
import type { CompanyRequest } from '../../api/types';

/**
 * Pasted text, read and then accepted a part at a time with the original beside it.
 *
 * **Nothing is written by reading.** The mail sweep files what only adds because nobody is
 * watching it; here somebody is, so every cargo, vessel, position and company goes through a
 * form — the same forms the other tabs use — with the text it came out of on screen. What is
 * never accepted is simply never saved, and closing this costs nothing.
 *
 * Two steps in one dialog rather than two dialogs, because going back to fix the paste is the
 * common correction: a signature cut off at the bottom, a second vessel paragraph missed.
 */
export default function PasteModal({
  open,
  modelUp,
  onClose,
}: {
  open: boolean;
  /** Whether the model server is answering. Without it only the company block is read. */
  modelUp: boolean;
  onClose: () => void;
}) {
  const [text, setText] = useState('');
  const [subject, setSubject] = useState('');
  const [draft, setDraft] = useState<IntakePasteDraft | null>(null);
  /** Which parts have been accepted, and what became of them — keyed "cargo:0", "company". */
  const [done, setDone] = useState<Record<string, string>>({});

  const read = useMutation({
    mutationFn: () => intakeApi.paste({ text, subject: subject.trim() || undefined }),
    onSuccess: (d) => {
      setDraft(d);
      setDone({});
    },
  });

  const close = () => {
    // The text survives a close so an accidental Escape does not lose a long paste; the
    // reading does not, since what it matched against may have changed by the next open.
    setDraft(null);
    setDone({});
    onClose();
  };

  const mark = (key: string, note: string) => setDone((d) => ({ ...d, [key]: note }));

  const reference = useMemo(() => <OriginalText text={text} />, [text]);

  const nothingRead =
    draft && !draft.company && draft.cargoes.length === 0 && draft.vessels.length === 0;

  return (
    <Modal
      open={open}
      onCancel={close}
      footer={null}
      width={draft ? 1400 : 820}
      title={draft ? 'Review what was read' : 'Paste text to read'}
      destroyOnClose
    >
      {!draft ? (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
            A cargo offer, a position list, a vessel's description or a company's full style —
            or all of them in one forwarded email. Nothing is saved by reading it: every part
            comes back as a draft to check against the text and accept on its own.
          </Typography.Paragraph>
          {!modelUp && (
            <Alert
              type="warning"
              showIcon
              message="The model server is not answering"
              description="Only the company details — name, address, people, emails and phones — can be read without it. Cargoes, vessels and positions need the model: start it in chartering-ml with make serve-docker."
            />
          )}
          <Input
            placeholder="Subject, if it came with one (optional — helps the model date 'prompt' and 'end month')"
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            maxLength={300}
          />
          <Input.TextArea
            autoFocus
            rows={16}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Paste the text here"
            maxLength={60000}
          />
          <Space>
            <Button
              type="primary"
              loading={read.isPending}
              disabled={!text.trim()}
              onClick={() => read.mutate()}
            >
              {read.isPending ? 'Reading…' : 'Read it'}
            </Button>
            <Button onClick={close}>Cancel</Button>
          </Space>
        </Space>
      ) : (
        <Row gutter={16}>
          <Col xs={24} md={10}>
            <Typography.Text type="secondary">The text as pasted</Typography.Text>
            <div style={{ maxHeight: '72vh', overflow: 'auto', marginTop: 8 }}>{reference}</div>
          </Col>
          <Col xs={24} md={14}>
            <div style={{ maxHeight: '76vh', overflow: 'auto', paddingRight: 4 }}>
              <Space direction="vertical" size="middle" style={{ width: '100%' }}>
                <Space wrap>
                  {draft.type && <Tag color="blue">{draft.type.replace(/_/g, ' ')}</Tag>}
                  {draft.summary && (
                    <Typography.Text type="secondary">{draft.summary}</Typography.Text>
                  )}
                  <Button size="small" onClick={() => setDraft(null)}>
                    Back to the text
                  </Button>
                </Space>
                {draft.modelError && <Alert type="warning" showIcon message={draft.modelError} />}
                {nothingRead && (
                  <Empty description="Nothing recognisable was read — no cargo, vessel or company details. Go back and check the paste." />
                )}

                {draft.company && (
                  <CompanyCard
                    draft={draft.company}
                    doneNote={done.company}
                    onDone={(note) => mark('company', note)}
                  />
                )}

                {draft.cargoes.map((c, i) => (
                  <CargoCard
                    key={`cargo-${i}`}
                    draft={c}
                    reference={reference}
                    doneNote={done[`cargo:${i}`]}
                    onDone={(note) => mark(`cargo:${i}`, note)}
                  />
                ))}

                {draft.vessels.map((v, i) => (
                  <VesselCard
                    key={`vessel-${i}`}
                    draft={v}
                    reference={reference}
                    vesselDone={done[`vessel:${i}`]}
                    positionDone={done[`position:${i}`]}
                    onVesselDone={(note) => mark(`vessel:${i}`, note)}
                    onPositionDone={(note) => mark(`position:${i}`, note)}
                  />
                ))}
              </Space>
            </div>
          </Col>
        </Row>
      )}
    </Modal>
  );
}

function OriginalText({ text }: { text: string }) {
  return (
    <pre
      style={{
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        fontSize: 12,
        margin: 0,
        padding: 12,
        border: '1px solid #f0f0f0',
        borderRadius: 4,
        background: '#fafafa',
      }}
    >
      {text}
    </pre>
  );
}

function DoneTag({ note }: { note?: string }) {
  return note ? (
    <Tag icon={<CheckCircleOutlined />} color="success">
      {note}
    </Tag>
  ) : null;
}

// ---------------------------------------------------------------------------- cargo

function CargoCard({
  draft,
  reference,
  doneNote,
  onDone,
}: {
  draft: PasteCargoDraft;
  reference: ReactNode;
  doneNote?: string;
  onDone: (note: string) => void;
}) {
  const [formOpen, setFormOpen] = useState(false);
  const c = draft.cargo;
  const quantity =
    c.quantity != null ? `${c.quantity.toLocaleString()} ${c.quantityUnit ?? ''}`.trim() : null;
  const route = [c.loadPortText ?? '?', c.dischargePortText ?? '?'].join(' → ');
  const laycan = c.laycanText ?? [c.laycanFrom, c.laycanTo].filter(Boolean).join(' / ');

  return (
    <Card size="small" title={<Space>Cargo<DoneTag note={doneNote} /></Space>}>
      <Space direction="vertical" size={6} style={{ width: '100%' }}>
        <Typography.Text strong>{[quantity, c.commodity].filter(Boolean).join(' ')}</Typography.Text>
        <Typography.Text type="secondary">
          {route}
          {laycan ? ` · laycan ${laycan}` : ''}
        </Typography.Text>
        {draft.chartererAsWritten && (
          <Typography.Text type="secondary">
            Charterer "{draft.chartererAsWritten}" is not a company on file — kept in the notes.
          </Typography.Text>
        )}
        {draft.duplicate && (
          <Alert
            type="warning"
            showIcon
            message={`Looks like a cargo already in hand: #${draft.duplicate.cargoId} ${draft.duplicate.commodity}`}
            description={`${draft.duplicate.reasons.join('; ')}. Saving makes a second record of the same enquiry — open that one on the Cargoes tab instead if it is.`}
          />
        )}
        <Button
          type={doneNote ? 'default' : 'primary'}
          onClick={() => setFormOpen(true)}
          disabled={!!doneNote}
        >
          Review and save the cargo
        </Button>
      </Space>
      <CargoForm
        open={formOpen}
        defaults={c}
        reference={reference}
        onClose={() => setFormOpen(false)}
        onSaved={(saved) => onDone(`Saved as cargo #${saved.id}`)}
      />
    </Card>
  );
}

// ---------------------------------------------------------------------------- vessel

function VesselCard({
  draft,
  reference,
  vesselDone,
  positionDone,
  onVesselDone,
  onPositionDone,
}: {
  draft: PasteVesselDraft;
  reference: ReactNode;
  vesselDone?: string;
  positionDone?: string;
  onVesselDone: (note: string) => void;
  onPositionDone: (note: string) => void;
}) {
  const v = draft.vessel;
  // Which hull the position goes on: the exact match, one of the suggestions picked here, or
  // the ship created from this card. Only then can a position be saved — against nothing, it
  // would be a position about a ship that does not exist.
  const [vesselId, setVesselId] = useState<number | undefined>(draft.match?.vesselId);
  const [vesselName, setVesselName] = useState<string | undefined>(draft.match?.name);
  const [vesselFormOpen, setVesselFormOpen] = useState(false);
  const [positionFormOpen, setPositionFormOpen] = useState(false);

  // Stable while the dialog is open: the form resets itself whenever its defaults change,
  // and a new object on every render would wipe what is being typed.
  const positionDefaults = useMemo(
    () => (draft.position ? { ...draft.position, vesselId } : undefined),
    [draft.position, vesselId],
  );

  const facts = [
    v.imoNumber && `IMO ${v.imoNumber}`,
    v.deadweightTonnage && `DWT ${v.deadweightTonnage.toLocaleString()}`,
    v.deadweightCargoCapacity && `DWCC ${v.deadweightCargoCapacity.toLocaleString()}`,
    v.yearBuilt && `built ${v.yearBuilt}`,
    v.flag,
  ].filter(Boolean);

  const p = draft.position;
  const where = p
    ? [p.openPortText, p.openText ?? [p.openFrom, p.openTo].filter(Boolean).join(' / ')]
        .filter(Boolean)
        .join(' · ')
    : null;

  return (
    <Card size="small" title={<Space>Vessel — {v.name}<DoneTag note={vesselDone} /></Space>}>
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        {facts.length > 0 && <Typography.Text type="secondary">{facts.join(' · ')}</Typography.Text>}

        {vesselId != null ? (
          <Space wrap>
            <Tag color="green">On file: {vesselName}</Tag>
            {draft.match && vesselId === draft.match.vesselId && (
              <Typography.Text type="secondary">
                matched by{' '}
                {draft.match.how === 'IMO' ? 'IMO' : draft.match.how === 'EX_NAME' ? 'a former name' : 'name'}
              </Typography.Text>
            )}
            {!draft.match && !vesselDone && (
              <Button
                size="small"
                type="link"
                onClick={() => {
                  setVesselId(undefined);
                  setVesselName(undefined);
                }}
              >
                not her after all
              </Button>
            )}
          </Space>
        ) : (
          <>
            <Alert
              type="info"
              showIcon
              message="No hull on file answers to this name or IMO"
              description={
                draft.suggestions?.length
                  ? 'These resemble her. Pick one if it is her, or create her as a new vessel.'
                  : 'Create her as a new vessel to record the position against.'
              }
            />
            {(draft.suggestions ?? []).map((s) => (
              <Space key={s.vesselId} wrap>
                <Button
                  size="small"
                  onClick={() => {
                    setVesselId(s.vesselId);
                    setVesselName(s.name);
                  }}
                >
                  It's her: {s.name}
                </Button>
                <Typography.Text type="secondary">
                  {s.imoNumber ? `IMO ${s.imoNumber} · ` : ''}
                  {s.reason}
                </Typography.Text>
              </Space>
            ))}
            <Button onClick={() => setVesselFormOpen(true)} disabled={!!vesselDone}>
              Not on file — review and create her
            </Button>
          </>
        )}

        {p && (
          <Card
            size="small"
            type="inner"
            title={<Space>Open position<DoneTag note={positionDone} /></Space>}
          >
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              <Typography.Text>{where || 'as written in the text'}</Typography.Text>
              <Tooltip title={vesselId == null ? 'Pick or create the vessel first' : undefined}>
                <Button
                  type={positionDone ? 'default' : 'primary'}
                  disabled={vesselId == null || !!positionDone}
                  onClick={() => setPositionFormOpen(true)}
                >
                  Review and save the position
                </Button>
              </Tooltip>
            </Space>
          </Card>
        )}
      </Space>

      <VesselForm
        open={vesselFormOpen}
        defaults={v}
        reference={reference}
        onClose={() => setVesselFormOpen(false)}
        onSaved={(saved) => {
          setVesselId(saved.id);
          setVesselName(saved.name);
          onVesselDone(`Created ${saved.name}`);
        }}
      />
      <PositionForm
        open={positionFormOpen}
        defaults={positionDefaults}
        reference={reference}
        onClose={() => setPositionFormOpen(false)}
        onSaved={() => onPositionDone('Position saved')}
      />
    </Card>
  );
}

// ---------------------------------------------------------------------------- company

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
function CompanyCard({
  draft,
  doneNote,
  onDone,
}: {
  draft: PasteCompanyDraft;
  doneNote?: string;
  onDone: (note: string) => void;
}) {
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
      return intakeApi.pasteCompany({
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

  return (
    <Card size="small" title={<Space>Company<DoneTag note={doneNote} /></Space>}>
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
                      {m.name}
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
            <Typography.Text strong>What the text says about {comparison.companyName}</Typography.Text>
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
        <Button
          type="primary"
          loading={accept.isPending}
          disabled={locked || (onFileCompany && !comparison)}
          onClick={() => accept.mutate()}
        >
          {onFileCompany ? 'Save to this company' : 'Create the company'}
        </Button>
      </Space>
    </Card>
  );
}
