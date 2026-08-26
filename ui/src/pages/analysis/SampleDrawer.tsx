import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Col,
  Drawer,
  Input,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { ExperimentOutlined, PaperClipOutlined, SaveOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import RecordActions from '../../components/RecordActions';
import { useAnalysisMutations, useAnalysisSample } from '../../analysis/store';
import { LABELS, STATUSES, labelMeta } from './labels';
import type { AnalysisLabel, AnalysisStatus } from '../../api/analysis';

/**
 * One sample, opened to be labelled.
 *
 * The screen is the email on the left and the answer on the right, side by side, and that
 * is the whole design: labelling is a reading task, and a form that made the reviewer
 * scroll away from the text to write down what is in it would produce annotations written
 * from memory. On a phone the two stack, text first.
 *
 * The email itself is never editable. A sample's body is a snapshot of what arrived, and a
 * corpus whose inputs have been tidied up is a record of nothing — the model would be
 * trained on emails no correspondent ever sent.
 */
export default function SampleDrawer({
  sampleId,
  templates,
  onClose,
}: {
  sampleId?: number;
  /** The starting shape per label, from the status endpoint. Suggestions, not a schema. */
  templates: Record<string, string>;
  onClose: () => void;
}) {
  const query = useAnalysisSample(sampleId);
  const { update, remove } = useAnalysisMutations();
  const detail = query.data;
  const s = detail?.sample;

  // The form's own copy, seeded from the fetch. A controlled editor is the only way the
  // "insert template" button can work at all, and it means an unsaved annotation survives
  // a background refetch of the list behind the drawer.
  const [label, setLabel] = useState<AnalysisLabel>('UNLABELLED');
  const [annotation, setAnnotation] = useState('');
  const [notes, setNotes] = useState('');

  useEffect(() => {
    if (!detail) return;
    setLabel(detail.sample.label);
    setAnnotation(detail.annotation ?? '');
    setNotes(detail.sample.notes ?? '');
  }, [detail]);

  const dirty =
    s != null &&
    (label !== s.label || annotation !== (detail?.annotation ?? '') || notes !== (s.notes ?? ''));

  const save = async (status?: AnalysisStatus) => {
    if (!sampleId) return;
    await update.mutateAsync({
      id: sampleId,
      body: { label, annotation, notes, ...(status ? { status } : {}) },
    });
    message.success(status === 'READY' ? 'Marked ready to train on' : 'Saved');
  };

  /**
   * Drop the label's skeleton into the editor.
   *
   * Refuses to overwrite work in progress rather than asking: the alternative is a
   * confirmation dialog on a button whose whole point is that it is cheap to press, and
   * losing ten minutes of typing to a mis-click is a worse failure than having to clear the
   * box first.
   */
  const insertTemplate = () => {
    const template = templates[label];
    if (!template) {
      message.info('Say what kind of email this is first — the shape follows from that.');
      return;
    }
    if (annotation.trim() && annotation.trim() !== template.trim()) {
      message.warning('There is already an annotation here. Clear it first to start over.');
      return;
    }
    setAnnotation(template);
  };

  return (
    <Drawer
      open={sampleId != null}
      onClose={onClose}
      width={1100}
      destroyOnClose
      title={
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, minWidth: 0 }}>
          <ExperimentOutlined style={{ marginTop: 5 }} />
          {/* Same shape as the mailbox drawer's header, and for the same reason: the
              buttons beside it are the fixed half, so the subject is the part that has to
              give way. width:0 with flex-grow keeps a 60-character report id out of the
              header's own measurement. */}
          <div style={{ flex: 1, width: 0 }}>
            <Typography.Paragraph
              strong
              ellipsis={{ rows: 2, tooltip: s?.subject || undefined }}
              style={{ margin: 0, overflowWrap: 'anywhere' }}
            >
              {s?.subject || '(no subject)'}
            </Typography.Paragraph>
          </div>
        </div>
      }
      extra={
        s && (
          <Space wrap>
            <Button
              icon={<SaveOutlined />}
              onClick={() => save()}
              loading={update.isPending}
              disabled={!dirty}
            >
              Save
            </Button>
            {/* The primary action, because reviewing a sample is what this screen is for
                and marking it fit to train on is what reviewing one leads to. The server
                refuses it without a label and an annotation, so there is nothing to guard
                here beyond saving first — Save and mark is one request either way. */}
            <Button
              type="primary"
              onClick={() => save('READY')}
              loading={update.isPending}
              disabled={s.status === 'READY' && !dirty}
            >
              {s.status === 'READY' ? 'Save (ready)' : 'Save and mark ready'}
            </Button>
          </Space>
        )
      }
    >
      {query.isLoading && <Spin />}
      {detail && s && (
        <Row gutter={16}>
          <Col xs={24} lg={13}>
            <Space direction="vertical" size={4} style={{ width: '100%', marginBottom: 12 }}>
              <Typography.Text type="secondary" style={{ fontSize: 13 }}>
                {s.fromName ? `${s.fromName} <${s.fromAddress ?? '—'}>` : s.fromAddress ?? '—'}
              </Typography.Text>
              <Space size={6} wrap>
                {s.receivedAt && (
                  <Tag>{dayjs(s.receivedAt).format('D MMM YYYY HH:mm')}</Tag>
                )}
                <Tooltip
                  title={
                    s.source === 'PASTED'
                      ? 'Added by hand — there is no message in the mailbox behind this one'
                      : 'Captured from synced mail'
                  }
                >
                  <Tag color={s.source === 'PASTED' ? 'gold' : 'default'}>
                    {s.source === 'PASTED' ? 'pasted' : 'from mailbox'}
                  </Tag>
                </Tooltip>
                <Tag>{s.bodyChars.toLocaleString()} chars</Tag>
                {s.attachmentNames && (
                  <Tooltip title={`Names only, and the files were never stored: ${s.attachmentNames}`}>
                    <Tag icon={<PaperClipOutlined />}>attachments</Tag>
                  </Tooltip>
                )}
              </Space>
            </Space>

            {/* Monospace and pre-wrapped, which for once is the accurate rendering rather
                than a stylistic choice: a position list is a table drawn with spaces, and a
                proportional font turns its columns into a paragraph. This is also exactly
                the text the model will be trained on — what the reviewer reads and what
                goes in the file are the same string. */}
            <pre
              style={{
                margin: 0,
                padding: 12,
                maxHeight: '62vh',
                overflow: 'auto',
                background: 'rgba(0,0,0,0.02)',
                border: '1px solid rgba(5,5,5,0.06)',
                borderRadius: 6,
                whiteSpace: 'pre-wrap',
                overflowWrap: 'anywhere',
                fontSize: 12.5,
                lineHeight: 1.5,
              }}
            >
              {detail.bodyText}
            </pre>
          </Col>

          <Col xs={24} lg={11}>
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <div>
                <Typography.Text strong>What kind of email is this?</Typography.Text>
                <Select<AnalysisLabel>
                  value={label}
                  onChange={setLabel}
                  style={{ width: '100%', marginTop: 4 }}
                  options={LABELS.map((l) => ({
                    value: l.value,
                    label: l.label,
                    title: l.hint,
                  }))}
                />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {labelMeta(label).hint}
                </Typography.Text>
              </div>

              <div>
                <Space
                  style={{ width: '100%', justifyContent: 'space-between' }}
                  align="baseline"
                >
                  <Typography.Text strong>
                    What should the model return?
                  </Typography.Text>
                  <Button size="small" onClick={insertTemplate}>
                    Insert template
                  </Button>
                </Space>
                <Input.TextArea
                  value={annotation}
                  onChange={(e) => setAnnotation(e.target.value)}
                  rows={18}
                  placeholder="JSON. Insert the template for this label and fill it in."
                  style={{ fontFamily: 'monospace', fontSize: 12.5, marginTop: 4 }}
                />
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Must be valid JSON — the server refuses anything else, so a broken line can
                  never reach a training file. Use "" for a detail the email does not give;
                  do not fill in what it does not say.
                </Typography.Text>
              </div>

              <div>
                <Typography.Text strong>Notes</Typography.Text>
                <Input.TextArea
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  rows={2}
                  placeholder="Why this one is odd. For you — never exported."
                  style={{ marginTop: 4 }}
                />
              </div>

              <div>
                <Typography.Text strong>Status</Typography.Text>
                {/* Fires on change rather than on Save, like every other immediate action in
                    this app — and the header's "mark ready" is the same call. Kept here as
                    well because putting one back to New or Skipped is a different intent
                    from finishing it, and should not need the primary button. */}
                <Select<AnalysisStatus>
                  value={s.status}
                  onChange={(status) =>
                    update.mutate({ id: s.id, body: { status } })
                  }
                  loading={update.isPending}
                  style={{ width: '100%', marginTop: 4 }}
                  options={STATUSES.map((st) => ({
                    value: st.value,
                    label: st.label,
                    title: st.hint,
                  }))}
                />
              </div>

              {s.status === 'READY' && !s.annotated && (
                <Alert
                  type="warning"
                  showIcon
                  message="Marked ready with no annotation"
                  description="This will not export. Write the answer, or put it back to New."
                />
              )}

              <RecordActions
                entity="sample"
                name={s.subject ?? s.fromAddress}
                deleteLoading={remove.isPending}
                onDelete={async () => {
                  await remove.mutateAsync(s.id);
                  onClose();
                }}
                deleteWarning={
                  <>
                    Only the copy kept for training goes — the email itself stays in the
                    mailbox. Note that the next capture over the same folder will bring it
                    back; <b>Skipped</b> is what keeps junk out for good.
                  </>
                }
              />
            </Space>
          </Col>
        </Row>
      )}
    </Drawer>
  );
}
