import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Col,
  Collapse,
  Descriptions,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Progress,
  Row,
  Select,
  Space,
  Statistic,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  SendOutlined,
  EyeOutlined,
  ExperimentOutlined,
  StopOutlined,
  ReloadOutlined,
  SaveOutlined,
  DeleteOutlined,
  SettingOutlined,
  HistoryOutlined,
  PauseCircleOutlined,
  PlayCircleOutlined,
  RedoOutlined,
  MailOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { campaignsApi } from '../../api/campaigns';
import { circulationsApi } from '../../api/circulations';
import { emailFootersApi, emailTemplatesApi } from '../../api/emailLibrary';
import { useCurrentList } from '../../circulations/store';
import RichTextEditor from '../../components/RichTextEditor';
import FooterManagerModal from './FooterManagerModal';
import HistoryModal from './HistoryModal';
import type {
  CampaignRecipient,
  CampaignState,
  CirculationListEntry,
  CirculationRun,
} from '../../api/types';

const DRAFT_KEY = 'chartering.circularDraft.v1';
/**
 * Runs the user has waved away. A run stays resumable for as long as it has anybody left
 * to reach — that is a fact about the run, not a preference — so "don't offer me this one
 * again" is kept here rather than written back to the server. Dismissed runs are still
 * reachable, and still resumable, from the History dropdown.
 */
const DISMISSED_KEY = 'chartering.dismissedResumable.v1';

const DEFAULT_BODY =
  '<p>Dear {{greeting}},</p><p><br></p><p>Please find below our current position list.</p>' +
  '<p><br></p><p>Best regards,<br>Chartering Desk</p>';

/** Terminal states get a colour so the outcome is readable at a glance. */
const STATE_COLOUR: Record<CampaignState, string> = {
  IDLE: 'default',
  RUNNING: 'processing',
  COMPLETED: 'success',
  COMPLETED_WITH_ERRORS: 'warning',
  CANCELLED: 'default',
  ABORTED: 'error',
  // Not terminal: both mean "stopped with people still to reach", which is a state you are
  // meant to act on rather than read and move past.
  PAUSED: 'warning',
  INTERRUPTED: 'warning',
};

function readDismissed(): number[] {
  try {
    const raw = JSON.parse(localStorage.getItem(DISMISSED_KEY) ?? '[]');
    return Array.isArray(raw) ? raw.filter((v): v is number => typeof v === 'number') : [];
  } catch {
    return [];
  }
}

function toRecipient(e: CirculationListEntry): CampaignRecipient {
  return {
    email: e.email,
    contactId: e.contactId,
    greetingName: e.greetingName,
    personName: e.personName,
    title: e.title,
    companyName: e.companyName,
  };
}

/**
 * Client-side mirror of the server's MailTemplateService merge rules, used only for the
 * preview. The authoritative render is the one the API produces at send time — use the
 * test send when you want to see exactly what a recipient will get.
 */
function renderPreview(html: string, r?: CampaignRecipient): string {
  const esc = (s: string) =>
    s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  const firstNonBlank = (...vals: (string | undefined)[]) =>
    vals.find((v) => v && v.trim())?.trim() ?? '';
  return html.replace(/\{\{\s*(\w+)\s*\}\}/g, (whole, key: string) => {
    switch (key.toLowerCase()) {
      case 'greeting':
        return esc(firstNonBlank(r?.greetingName, r?.personName, 'Sirs'));
      case 'name':
        return esc(firstNonBlank(r?.personName, r?.greetingName));
      case 'title':
        return esc(firstNonBlank(r?.title));
      case 'company':
        return esc(firstNonBlank(r?.companyName));
      case 'email':
        return esc(firstNonBlank(r?.email));
      default:
        return whole; // unknown token stays visible rather than vanishing
    }
  });
}

function humanDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`;
  const m = Math.floor(seconds / 60);
  if (m >= 90) {
    const h = Math.floor(m / 60);
    const rem = m % 60;
    return rem ? `${h}h ${rem}m` : `${h}h`;
  }
  const s = Math.round(seconds % 60);
  return s ? `${m}m ${s}s` : `${m}m`;
}

/** Clock time only — a pause between runs is always today or tonight, never a date. */
function clockTime(iso: string): string {
  return iso.replace('T', ' ').slice(11, 16);
}

export default function CircularsPage() {
  const currentList = useCurrentList();
  const entries = currentList.entries;
  const { message } = App.useApp();
  const qc = useQueryClient();

  const [subject, setSubject] = useState('');
  const [body, setBody] = useState(DEFAULT_BODY);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [testTo, setTestTo] = useState('');
  const [draftLoaded, setDraftLoaded] = useState(false);
  const [templateId, setTemplateId] = useState<number | null>(null);
  const [footerId, setFooterId] = useState<number | null>(null);
  const [footerPicked, setFooterPicked] = useState(false);
  const [footersOpen, setFootersOpen] = useState(false);
  const [historyRunId, setHistoryRunId] = useState<number>();
  const [dismissed, setDismissed] = useState<number[]>(readDismissed);

  // Restore the draft so a reload mid-compose doesn't lose the circular.
  useEffect(() => {
    try {
      const raw = localStorage.getItem(DRAFT_KEY);
      if (raw) {
        const d = JSON.parse(raw);
        if (typeof d?.subject === 'string') setSubject(d.subject);
        if (typeof d?.body === 'string' && d.body) setBody(d.body);
      }
    } catch {
      /* ignore a corrupt draft */
    }
    setDraftLoaded(true);
  }, []);

  useEffect(() => {
    if (!draftLoaded) return;
    try {
      localStorage.setItem(DRAFT_KEY, JSON.stringify({ subject, body }));
    } catch {
      /* storage full — the draft just won't survive a reload */
    }
  }, [subject, body, draftLoaded]);

  const templatesQ = useQuery({ queryKey: ['email-templates'], queryFn: emailTemplatesApi.list });
  const footersQ = useQuery({ queryKey: ['email-footers'], queryFn: emailFootersApi.list });

  // Pre-select the default footer, but only once — after that the user's choice wins, so
  // deliberately picking "No footer" isn't undone on the next render or refetch.
  useEffect(() => {
    if (footerPicked || !footersQ.data) return;
    const def = footersQ.data.find((f) => f.defaultFooter);
    if (def) setFooterId(def.id);
    setFooterPicked(true);
  }, [footersQ.data, footerPicked]);

  const selectedFooter = footersQ.data?.find((f) => f.id === footerId);

  const configQ = useQuery({ queryKey: ['campaign', 'config'], queryFn: campaignsApi.config });
  const placeholdersQ = useQuery({
    queryKey: ['campaign', 'placeholders'],
    queryFn: campaignsApi.placeholders,
    staleTime: 1000 * 60 * 30,
  });

  const statusQ = useQuery({ queryKey: ['campaign', 'status'], queryFn: campaignsApi.status });
  const running = statusQ.data?.running ?? false;

  // Poll only while something is in flight; idle tabs shouldn't hammer the API.
  useEffect(() => {
    if (!running) return;
    const id = setInterval(() => {
      qc.invalidateQueries({ queryKey: ['campaign', 'status'] });
      qc.invalidateQueries({ queryKey: ['campaign', 'log'] });
      qc.invalidateQueries({ queryKey: ['circulations', 'today'] });
    }, 1500);
    return () => clearInterval(id);
  }, [running, qc]);

  const logQ = useQuery({ queryKey: ['campaign', 'log'], queryFn: campaignsApi.log });

  /**
   * Circulations that stopped with people still to reach. Asked for on load rather than
   * derived from status, because status lives in the API's memory and this is the case
   * where that memory is gone — the API was restarted mid-send, and only the recipient
   * rows in the database know how far it got.
   */
  const resumableQ = useQuery({
    queryKey: ['campaign', 'resumable'],
    queryFn: campaignsApi.resumable,
    enabled: !running,
  });
  useEffect(() => {
    if (!running) qc.invalidateQueries({ queryKey: ['campaign', 'resumable'] });
  }, [running, qc]);

  const stopped = (resumableQ.data ?? []).filter((r) => !dismissed.includes(r.id));

  const dismiss = (id: number) => {
    // Pruned against what is actually resumable, so the list can't grow without bound as
    // runs are finished off or deleted.
    const live = (resumableQ.data ?? []).map((r) => r.id);
    const next = [...dismissed.filter((d) => live.includes(d)), id];
    setDismissed(next);
    try {
      localStorage.setItem(DISMISSED_KEY, JSON.stringify(next));
    } catch {
      /* storage full — the banner just comes back on the next reload */
    }
  };

  // The permanent record. Refetched when a run finishes, since that is when a new entry
  // appears — polling it while idle would be a request per tab per interval for nothing.
  const historyQ = useQuery({
    queryKey: ['circulations', 'history'],
    queryFn: () => circulationsApi.history({ size: 50 }),
  });
  useEffect(() => {
    if (!running) {
      qc.invalidateQueries({ queryKey: ['circulations', 'history'] });
      qc.invalidateQueries({ queryKey: ['circulations', 'today'] });
    }
  }, [running, qc]);

  /**
   * How much has already gone out today, across every circulation. The pacing rails hold a
   * send inside the mailbox's per-hour allowance and nothing holds it inside the daily one,
   * so this is the number to look at before starting another circular.
   *
   * Counted server-side in the server's local day: the answer must not depend on which
   * machine has the tab open. Refetched on a running campaign by the polling effect above,
   * so the figure climbs as the messages leave.
   */
  const todayQ = useQuery({
    queryKey: ['circulations', 'today'],
    queryFn: circulationsApi.today,
  });
  const sentToday = todayQ.data?.sent;

  const cfg = configQ.data;
  const recipients = useMemo(() => entries.map(toRecipient), [entries]);

  // Gaps are random, so the estimate uses the mean of the range.
  const averageDelayMs = cfg ? (cfg.minDelayMs + cfg.maxDelayMs) / 2 : 6500;
  const delayRange = cfg
    ? `${(cfg.minDelayMs / 1000).toFixed(0)}–${(cfg.maxDelayMs / 1000).toFixed(0)}s`
    : '3–10s';

  // Over the per-run cap the server splits the send into runs and waits between them, so
  // the estimate has to count those pauses — they are most of the elapsed time on a big
  // list, and quoting the message time alone would promise minutes for a job of hours.
  const perRun = cfg?.maxRecipientsPerCampaign ?? recipients.length;
  const batchCount = recipients.length ? Math.ceil(recipients.length / Math.max(1, perRun)) : 1;
  const split = batchCount > 1;
  const estimateSeconds =
    (recipients.length > 1 ? ((recipients.length - 1) * averageDelayMs) / 1000 : 0) +
    ((batchCount - 1) * (cfg?.batchPauseMs ?? 0)) / 1000;
  const pauseLabel = humanDuration((cfg?.batchPauseMs ?? 0) / 1000);

  // targetId is passed in rather than read from state: the caller may have just decided
  // this is a "save as copy", and a setState wouldn't have applied by the time this runs.
  const saveTemplateMut = useMutation({
    mutationFn: ({ name, targetId }: { name: string; targetId: number | null }) => {
      const payload = { name, subject, bodyHtml: body };
      return targetId != null
        ? emailTemplatesApi.update(targetId, payload)
        : emailTemplatesApi.create(payload);
    },
    onSuccess: (saved) => {
      setTemplateId(saved.id);
      message.success(`Template "${saved.name}" saved`);
      qc.invalidateQueries({ queryKey: ['email-templates'] });
    },
  });

  const deleteTemplateMut = useMutation({
    mutationFn: (id: number) => emailTemplatesApi.remove(id),
    onSuccess: () => {
      setTemplateId(null);
      message.success('Template deleted');
      qc.invalidateQueries({ queryKey: ['email-templates'] });
    },
  });

  const applyTemplate = (id: number | null) => {
    setTemplateId(id);
    const t = templatesQ.data?.find((x) => x.id === id);
    if (!t) return;
    setSubject(t.subject ?? '');
    setBody(t.bodyHtml);
  };

  const promptSaveTemplate = () => {
    const current = templatesQ.data?.find((t) => t.id === templateId);
    const name = window.prompt(
      current ? 'Save over this template, or type a new name to save a copy:' : 'Name this template:',
      current?.name ?? '',
    );
    if (!name?.trim()) return;
    // Keeping the name overwrites the selected template; changing it saves a copy.
    const sameName = current && name.trim().toLowerCase() === current.name.toLowerCase();
    saveTemplateMut.mutate({ name: name.trim(), targetId: sameName ? current.id : null });
  };

  const startMut = useMutation({
    mutationFn: () =>
      campaignsApi.start({
        subject,
        htmlBody: body,
        recipients,
        footerId,
        listId: currentList.listId,
      }),
    onSuccess: () => {
      message.success('Campaign started');
      refreshAfterLaunch();
    },
  });

  const cancelMut = useMutation({
    mutationFn: campaignsApi.cancel,
    onSuccess: () => {
      message.info('Stopping after the current message');
      qc.invalidateQueries({ queryKey: ['campaign', 'status'] });
    },
  });

  const pauseMut = useMutation({
    mutationFn: campaignsApi.pause,
    onSuccess: () => {
      message.info('Pausing after the current message');
      qc.invalidateQueries({ queryKey: ['campaign', 'status'] });
    },
  });

  const refreshAfterLaunch = () => {
    qc.invalidateQueries({ queryKey: ['campaign', 'status'] });
    qc.invalidateQueries({ queryKey: ['campaign', 'log'] });
    qc.invalidateQueries({ queryKey: ['campaign', 'resumable'] });
    qc.invalidateQueries({ queryKey: ['circulations', 'history'] });
    qc.invalidateQueries({ queryKey: ['circulations', 'today'] });
  };

  const resumeMut = useMutation({
    mutationFn: (runId: number) => campaignsApi.resume(runId),
    onSuccess: (s) => {
      message.success(`Resumed — ${Math.max(0, s.total - s.sent - s.failed)} left to send`);
      refreshAfterLaunch();
    },
  });

  const restartMut = useMutation({
    mutationFn: (runId: number) => campaignsApi.restart(runId),
    onSuccess: () => {
      message.success('Restarted — sending the whole circulation again');
      refreshAfterLaunch();
    },
  });

  const testMut = useMutation({
    mutationFn: () => campaignsApi.test(testTo, { subject, htmlBody: body, recipients, footerId }),
    onSuccess: () => message.success(`Test sent to ${testTo}`),
  });

  const status = statusQ.data;
  const busy = running || resumeMut.isPending || restartMut.isPending;
  const composeDisabled = running;
  const canSend = !!cfg?.ready && !running && recipients.length > 0 && subject.trim().length > 0;

  const progressPercent =
    status && status.total > 0
      ? Math.round(((status.sent + status.failed) / status.total) * 100)
      : 0;

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      {configQ.isSuccess && !cfg?.enabled && (
        <Alert
          type="warning"
          showIcon
          message="Sending is switched off"
          description="Set MAIL_ENABLED=true in .env, along with the credentials for whichever flow you use — the mailbox login, or BREVO_API_KEY — then restart the api container. Everything else on this page still works: you can compose and preview now."
        />
      )}
      {configQ.isSuccess && cfg?.enabled && !cfg.ready && (
        <Alert
          type="error"
          showIcon
          message={`${cfg.providerLabel} is not fully configured`}
          description={`Still missing: ${(cfg.missingSettings ?? []).join(', ')}. Fix it in .env, or pick the other flow on the Settings tab.`}
        />
      )}

      {stopped.map((run) => (
        <Alert
          key={run.id}
          type="warning"
          showIcon
          message={`"${run.subject}" stopped with ${run.pending} recipient${run.pending === 1 ? '' : 's'} still to reach`}
          description={
            <Space direction="vertical" size="small">
              <span>
                {run.sent} sent and {run.failed} failed out of {run.total}, started{' '}
                {run.startedAt.replace('T', ' ').slice(0, 16)}.{' '}
                {run.state === 'INTERRUPTED'
                  ? 'The API was restarted while it was sending.'
                  : (run.message ?? '')}{' '}
                Resuming sends only to the {run.pending} it never reached.
              </span>
              <Space wrap>
                <Popconfirm
                  title="Carry on from where it stopped?"
                  description={`${run.pending} message${run.pending === 1 ? '' : 's'} — nobody already reached is mailed twice.`}
                  okText="Resume"
                  onConfirm={() => resumeMut.mutate(run.id)}
                  disabled={busy || !cfg?.ready}
                >
                  <Button
                    type="primary"
                    icon={<PlayCircleOutlined />}
                    loading={resumeMut.isPending && resumeMut.variables === run.id}
                    disabled={busy || !cfg?.ready}
                  >
                    Resume ({run.pending})
                  </Button>
                </Popconfirm>
                <Button onClick={() => setHistoryRunId(run.id)}>See who was reached</Button>
                <Button type="text" onClick={() => dismiss(run.id)}>
                  Not now
                </Button>
              </Space>
            </Space>
          }
        />
      ))}

      <Card
        title="Compose circular"
        extra={
          <Space size="small" wrap>
            {/* Today's outgoing volume, first in the row because it is the one number to
                check before pressing Send: the tags beside it describe the per-hour pacing,
                and nothing in the app watches the mailbox's daily cap. */}
            <Tooltip
              title={
                todayQ.data ? (
                  <>
                    {todayQ.data.sent} circular email{todayQ.data.sent === 1 ? '' : 's'}{' '}
                    delivered since midnight ({todayQ.data.date}), over{' '}
                    {todayQ.data.circulations} circulation
                    {todayQ.data.circulations === 1 ? '' : 's'}. Counted in the server's local
                    time. Check it against your mailbox's daily cap before sending another —
                    the pacing above protects the per-hour allowance only.
                  </>
                ) : (
                  "How many circular emails have gone out today, in the server's local time"
                )
              }
            >
              <Tag
                icon={<MailOutlined />}
                color={sentToday ? 'green' : 'default'}
                style={{ marginInlineEnd: 0 }}
              >
                {sentToday ?? '—'} sent today
              </Tag>
            </Tooltip>
            {cfg && (
              <>
                {/* Which flow is sending, first among the config tags: it decides what the
                    recipient sees in their inbox and whose quota the circular spends, and
                    nothing else on this page would give it away. */}
                <Tooltip
                  title={
                    cfg.provider === 'BREVO'
                      ? "Sent through Brevo's transactional API. Brevo does the delivering, so this " +
                        'will not appear in your mailbox’s Sent folder, and bounces are recorded ' +
                        'against the Brevo account rather than your own mailbox. Change it on the ' +
                        'Settings tab.'
                      : 'Sent one at a time from your own mailbox over SMTP, as if written by hand. ' +
                        "Your mailbox’s quota and reputation are what is being spent. Change it " +
                        'on the Settings tab.'
                  }
                >
                  <Tag
                    icon={<SendOutlined />}
                    color={cfg.provider === 'BREVO' ? 'purple' : 'blue'}
                    style={{ marginInlineEnd: 0 }}
                  >
                    via {cfg.providerLabel}
                  </Tag>
                </Tooltip>
                {cfg.provider === 'SMTP' && (
                  <Tag>{cfg.smtpHost}:{cfg.smtpPort}</Tag>
                )}
                <Tag color="blue">1 email every {delayRange} (random)</Tag>
                <Tag>
                  max {cfg.maxRecipientsPerCampaign} per run
                  {cfg.batchPauseMs > 0 && `, ${humanDuration(cfg.batchPauseMs / 1000)} between`}
                </Tag>
              </>
            )}
            <Select<number>
              style={{ minWidth: 300 }}
              placeholder={
                historyQ.data?.content.length
                  ? `History (${historyQ.data.totalElements} circulation${historyQ.data.totalElements === 1 ? '' : 's'})`
                  : 'History — nothing sent yet'
              }
              // Reset after opening: the dropdown is a launcher, not a selection, and a
              // stuck value would make reopening the same run impossible.
              value={null as unknown as number}
              loading={historyQ.isLoading}
              disabled={!historyQ.data?.content.length}
              suffixIcon={<HistoryOutlined />}
              onChange={(id) => setHistoryRunId(id)}
              options={(historyQ.data?.content ?? []).map((h) => ({
                value: h.id,
                label: h.subject,
                run: h,
              }))}
              // Two lines per row: what was sent and when, then how it went. The dropdown
              // is how a past circular gets found again, so it has to be scannable.
              optionRender={(opt) => {
                const h = (opt.data as { run: CirculationRun }).run;
                return (
                  <Space direction="vertical" size={0}>
                    <Typography.Text ellipsis style={{ maxWidth: 380 }}>
                      {h.subject}
                    </Typography.Text>
                    <Space size={4}>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {h.startedAt.replace('T', ' ').slice(0, 16)}
                      </Typography.Text>
                      <Tag color={STATE_COLOUR[h.state]} style={{ marginInlineEnd: 0 }}>
                        {h.sent}/{h.total} sent
                      </Tag>
                      {h.failed > 0 && <Tag color="error">{h.failed} failed</Tag>}
                      {h.listName && <Tag>{h.listName}</Tag>}
                    </Space>
                  </Space>
                );
              }}
            />
          </Space>
        }
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {cfg?.fromAddress && (
            <Descriptions size="small" column={{ xs: 1, sm: 2, md: 3 }}>
              <Descriptions.Item label="From">
                {cfg.fromName} &lt;{cfg.fromAddress}&gt;
              </Descriptions.Item>
              {cfg.replyTo && <Descriptions.Item label="Reply-To">{cfg.replyTo}</Descriptions.Item>}
              <Descriptions.Item label="Recipients">
                {recipients.length} on the current list
              </Descriptions.Item>
            </Descriptions>
          )}

          {/* Everything to do with the recipient list — building it, saving it, loading a
              saved one — lives on the Circulation lists tab. This tab reads the current
              list and sends it, so there is exactly one place that decides who gets mailed. */}

          <Row gutter={[8, 8]} align="middle">
            <Col flex="auto">
              <Space wrap>
                <Select<number | null>
                  style={{ minWidth: 240 }}
                  placeholder="Load a saved template…"
                  allowClear
                  value={templateId}
                  loading={templatesQ.isLoading}
                  onChange={(v) => applyTemplate(v ?? null)}
                  options={(templatesQ.data ?? []).map((t) => ({ value: t.id, label: t.name }))}
                  disabled={composeDisabled}
                />
                <Tooltip title="Save the current subject and body as a template">
                  <Button
                    icon={<SaveOutlined />}
                    loading={saveTemplateMut.isPending}
                    disabled={composeDisabled || !body.trim()}
                    onClick={promptSaveTemplate}
                  >
                    Save template
                  </Button>
                </Tooltip>
                {templateId != null && (
                  <Popconfirm
                    title="Delete this template?"
                    onConfirm={() => deleteTemplateMut.mutate(templateId)}
                  >
                    <Button danger icon={<DeleteOutlined />} disabled={composeDisabled} />
                  </Popconfirm>
                )}
              </Space>
            </Col>
            <Col>
              <Space wrap>
                <Select<number | null>
                  style={{ minWidth: 200 }}
                  placeholder="Footer…"
                  value={footerId}
                  loading={footersQ.isLoading}
                  onChange={(v) => setFooterId(v ?? null)}
                  disabled={composeDisabled}
                  options={[
                    { value: null as number | null, label: 'No footer' },
                    ...(footersQ.data ?? []).map((f) => ({
                      value: f.id as number | null,
                      label: f.defaultFooter ? `${f.name} (default)` : f.name,
                    })),
                  ]}
                />
                <Tooltip title="Create and edit footers">
                  <Button icon={<SettingOutlined />} onClick={() => setFootersOpen(true)} />
                </Tooltip>
              </Space>
            </Col>
          </Row>

          <Input
            size="large"
            placeholder="Subject — placeholders work here too, e.g. Position list for {{company}}"
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            disabled={composeDisabled}
            maxLength={300}
          />

          <RichTextEditor
            value={body}
            onChange={setBody}
            placeholders={placeholdersQ.data}
            disabled={composeDisabled}
          />

          <Row gutter={[8, 8]} align="middle" justify="space-between">
            <Col>
              <Space wrap>
                <Button icon={<EyeOutlined />} onClick={() => setPreviewOpen(true)}>
                  Preview
                </Button>
                <Input
                  style={{ width: 260 }}
                  placeholder="your.address@example.com"
                  value={testTo}
                  onChange={(e) => setTestTo(e.target.value)}
                  disabled={composeDisabled}
                />
                <Button
                  icon={<ExperimentOutlined />}
                  loading={testMut.isPending}
                  disabled={composeDisabled || !cfg?.ready || !testTo.trim() || !subject.trim()}
                  onClick={() => testMut.mutate()}
                >
                  Send test to myself
                </Button>
              </Space>
            </Col>
            <Col>
              <Popconfirm
                title="Send this circular?"
                description={
                  <div style={{ maxWidth: 340 }}>
                    {recipients.length} separate message{recipients.length === 1 ? '' : 's'}, one per
                    recipient — no CC or BCC. Sent at a random {delayRange} apart
                    {split && (
                      <>
                        , in <b>{batchCount} runs</b> of up to {perRun} with {pauseLabel} between them
                      </>
                    )}
                    , so this takes roughly <b>{humanDuration(estimateSeconds)}</b>.
                    {split && (
                      <div style={{ marginTop: 8 }}>
                        You can pause it at any point, and a restart of the API stops it rather
                        than losing it — either way it picks up from where it stopped, with
                        nobody mailed twice.
                      </div>
                    )}
                  </div>
                }
                okText="Send"
                onConfirm={() => startMut.mutate()}
                disabled={!canSend}
              >
                <Button
                  type="primary"
                  size="large"
                  icon={<SendOutlined />}
                  loading={startMut.isPending}
                  disabled={!canSend}
                >
                  Send to {recipients.length}
                </Button>
              </Popconfirm>
            </Col>
          </Row>

          {split && (
            <Alert
              type="info"
              showIcon
              message={`${recipients.length} recipients — sent as ${batchCount} runs of up to ${perRun}, ${pauseLabel} apart (about ${humanDuration(estimateSeconds)})`}
              description={
                <>
                  Splitting keeps each burst inside the mailbox's per-hour allowance. It does not
                  fit the campaign inside a <b>daily</b> limit — {recipients.length} messages still
                  count as {recipients.length} against your Zoho plan's daily cap, and exceeding
                  that can suspend outgoing mail on the account. Trim the list or change the run
                  size and pause in Settings.
                </>
              }
            />
          )}
          {recipients.length === 0 && (
            <Empty description="No recipients. Build the current list on the Circulation lists tab, or add contacts from the Companies, Vessels or People tabs." />
          )}
        </Space>
      </Card>

      {status && status.state !== 'IDLE' && (
        <Card
          title={
            <Space>
              Campaign
              <Tag color={STATE_COLOUR[status.state]}>{status.state.replace(/_/g, ' ')}</Tag>
              {status.batchCount > 1 && (
                <Tag color={status.paused ? 'warning' : 'blue'}>
                  run {status.batch} of {status.batchCount}
                  {status.paused && status.nextBatchAt && ` — next at ${clockTime(status.nextBatchAt)}`}
                </Tag>
              )}
            </Space>
          }
          extra={
            <Space>
              <Button
                icon={<ReloadOutlined />}
                onClick={() => {
                  qc.invalidateQueries({ queryKey: ['campaign', 'status'] });
                  qc.invalidateQueries({ queryKey: ['campaign', 'log'] });
                }}
              >
                Refresh
              </Button>
              {running && (
                <>
                  <Popconfirm
                    title="Pause after the current message?"
                    description={
                      <div style={{ maxWidth: 320 }}>
                        The rest of the list is kept. You can carry on later — even after the
                        API is restarted.
                      </div>
                    }
                    okText="Pause"
                    onConfirm={() => pauseMut.mutate()}
                  >
                    <Button icon={<PauseCircleOutlined />} loading={pauseMut.isPending}>
                      Pause
                    </Button>
                  </Popconfirm>
                  <Popconfirm
                    title="Stop after the current message?"
                    description={
                      <div style={{ maxWidth: 320 }}>
                        Closes the run. Whoever it never reached is still recorded, so this can
                        be picked up again from the History dropdown if you change your mind.
                      </div>
                    }
                    onConfirm={() => cancelMut.mutate()}
                  >
                    <Button danger icon={<StopOutlined />} loading={cancelMut.isPending}>
                      Cancel
                    </Button>
                  </Popconfirm>
                </>
              )}
              {!running && status?.runId != null && (
                <>
                  {status.resumable && (
                    <Button
                      type="primary"
                      icon={<PlayCircleOutlined />}
                      loading={resumeMut.isPending}
                      disabled={busy || !cfg?.ready}
                      onClick={() => resumeMut.mutate(status.runId!)}
                    >
                      Resume ({Math.max(0, status.total - status.sent - status.failed)})
                    </Button>
                  )}
                  <Popconfirm
                    title="Send this circulation again, from the top?"
                    description={
                      <div style={{ maxWidth: 320 }}>
                        All {status.total} recipient{status.total === 1 ? '' : 's'} are mailed
                        again, including everyone who already received it. Recorded as a new
                        circulation — this one stays as it is.
                      </div>
                    }
                    okText="Restart"
                    onConfirm={() => restartMut.mutate(status.runId!)}
                    disabled={busy || !cfg?.ready}
                  >
                    <Button
                      icon={<RedoOutlined />}
                      loading={restartMut.isPending}
                      disabled={busy || !cfg?.ready}
                    >
                      Restart
                    </Button>
                  </Popconfirm>
                </>
              )}
            </Space>
          }
        >
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Progress
              percent={progressPercent}
              status={
                status.state === 'ABORTED'
                  ? 'exception'
                  : running
                    ? status.paused
                      ? 'normal' // no animation while nothing is leaving
                      : 'active'
                    : status.failed > 0
                      ? 'normal'
                      : 'success'
              }
            />
            <Row gutter={16}>
              <Col span={6}>
                <Statistic title="Sent" value={status.sent} suffix={`/ ${status.total}`} />
              </Col>
              <Col span={6}>
                <Statistic
                  title="Failed"
                  value={status.failed}
                  valueStyle={status.failed ? { color: '#cf1322' } : undefined}
                />
              </Col>
              <Col span={6}>
                <Statistic title="Skipped (duplicates)" value={status.skipped} />
              </Col>
              <Col span={6}>
                <Statistic
                  title={running ? 'Time remaining' : 'Finished'}
                  value={
                    running
                      ? status.etaSeconds != null
                        ? humanDuration(status.etaSeconds)
                        : '—'
                      : (status.finishedAt?.replace('T', ' ').slice(0, 19) ?? '—')
                  }
                />
              </Col>
            </Row>

            {running && status.paused && (
              <Alert
                type="warning"
                showIcon
                message={
                  status.nextBatchAt
                    ? `Between runs — run ${status.batch} of ${status.batchCount} starts at ${clockTime(status.nextBatchAt)}`
                    : `Between runs — run ${status.batch} of ${status.batchCount} is next`
                }
                description="Nothing is being sent right now. Pause or Cancel both stop the campaign here — either way the runs still to come are kept, and can be resumed."
              />
            )}
            {running && !status.paused && status.currentEmail && (
              <Typography.Text type="secondary">Sending to {status.currentEmail}…</Typography.Text>
            )}
            {status.message && (
              <Alert
                type={
                  status.state === 'COMPLETED'
                    ? 'success'
                    : status.state === 'ABORTED'
                      ? 'error'
                      : 'info'
                }
                showIcon
                message={status.message}
                description={status.lastError ? `Last error: ${status.lastError}` : undefined}
              />
            )}

            <Collapse
              defaultActiveKey={running ? ['log'] : []}
              items={[
                {
                  key: 'log',
                  label: 'Campaign log',
                  children: (
                    <pre
                      style={{
                        margin: 0,
                        maxHeight: 320,
                        overflow: 'auto',
                        fontSize: 12,
                        whiteSpace: 'pre-wrap',
                      }}
                    >
                      {logQ.data || 'No log yet.'}
                    </pre>
                  ),
                },
              ]}
            />
          </Space>
        </Card>
      )}

      <Modal
        open={previewOpen}
        onCancel={() => setPreviewOpen(false)}
        footer={null}
        width={760}
        title={`Preview — as ${recipients[0]?.email ?? 'a sample recipient'} would see it`}
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          Subject: {renderPreview(subject, recipients[0]) || <i>(empty)</i>}
        </Typography.Paragraph>
        <div
          style={{ border: '1px solid #f0f0f0', borderRadius: 4, padding: 16 }}
          // Footer appended exactly as the server does it, so the preview shows the
          // whole message rather than the body alone.
          dangerouslySetInnerHTML={{
            __html: renderPreview(body + (selectedFooter?.html ?? ''), recipients[0]),
          }}
        />
      </Modal>

      <FooterManagerModal open={footersOpen} onClose={() => setFootersOpen(false)} />
      <HistoryModal runId={historyRunId} onClose={() => setHistoryRunId(undefined)} />
    </Space>
  );
}
