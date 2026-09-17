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
import CompanyCard, { DoneTag } from './CompanyStyleCard';
import { intakeApi } from '../../api/intake';
import type {
  IntakePasteDraft,
  PasteCargoDraft,
  PasteVesselDraft,
} from '../../api/intake';

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
