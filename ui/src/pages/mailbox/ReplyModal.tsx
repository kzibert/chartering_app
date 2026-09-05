import { useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Descriptions,
  Input,
  Modal,
  Select,
  Space,
  Tooltip,
  Typography,
} from 'antd';
import { SendOutlined, SettingOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { campaignsApi } from '../../api/campaigns';
import { emailFootersApi } from '../../api/emailLibrary';
import RichTextEditor from '../../components/RichTextEditor';
import FooterManagerModal from '../circulars/FooterManagerModal';
import { useMailMessageMutations } from '../../mailbox/store';
import type { MailMessageDetail } from '../../api/types';

interface Props {
  open: boolean;
  /** The message being answered, already loaded by the drawer — never fetched again here. */
  detail?: MailMessageDetail;
  onClose: () => void;
}

/** "Re: x" once, however many times a thread has been round. */
function replySubject(subject?: string): string {
  const s = (subject ?? '').trim();
  if (!s) return 'Re:';
  return /^re\s*:/i.test(s) ? s : `Re: ${s}`;
}

/**
 * Answering one message, from the app.
 *
 * <p>The composer is deliberately thin: the footer, the quoted original and the mail-merge
 * are all applied server-side, so what is stored as having been sent is the same string the
 * mail server was handed. This screen picks which footer and whether to quote — it never
 * builds the message. That also keeps the editor holding only what the user actually wrote,
 * so a 100KB Outlook chain is not something they have to scroll past to type.
 *
 * <p>Sending goes through the mailbox over SMTP whatever the Circulars tab is set to. There
 * is no provider choice here and there should not be one: a reply has to come from the
 * address the correspondent wrote to.
 */
export default function ReplyModal({ open, detail, onClose }: Props) {
  const { message: toast } = App.useApp();
  const { reply } = useMailMessageMutations();

  const [to, setTo] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [footerId, setFooterId] = useState<number | null>(null);
  const [includeOriginal, setIncludeOriginal] = useState(true);
  const [footersOpen, setFootersOpen] = useState(false);

  const cfgQ = useQuery({ queryKey: ['campaign', 'config'], queryFn: campaignsApi.config });
  const placeholdersQ = useQuery({
    queryKey: ['campaign', 'placeholders'],
    queryFn: campaignsApi.placeholders,
  });
  const footersQ = useQuery({
    queryKey: ['email-footers'],
    queryFn: emailFootersApi.list,
    enabled: open,
  });

  const m = detail?.message;

  // Every open starts from the message, not from whatever the last reply left behind: the
  // drawer stays mounted between messages, and a half-written answer to somebody else is
  // the one thing that must never appear in this box.
  useEffect(() => {
    if (!open || !m) return;
    setTo(m.fromAddress);
    setSubject(replySubject(m.subject));
    setBody('');
    setIncludeOriginal(true);
    setFooterId(null);
  }, [open, m?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  // The reply default, applied when the list arrives — and only while the box is still
  // empty of a choice, so it cannot overwrite a footer picked a second earlier.
  useEffect(() => {
    if (!open || !footersQ.data || footerId != null) return;
    const def = footersQ.data.find((f) => f.replyDefault);
    if (def) setFooterId(def.id);
  }, [open, footersQ.data]); // eslint-disable-line react-hooks/exhaustive-deps

  const cfg = cfgQ.data;
  // The same two conditions the Circulars tab refuses to send under, asked here rather than
  // discovered by a 503: with sending off, or a setting missing, there is nothing this
  // screen can do about it and no reason to let somebody type an answer first.
  //
  // Asked of the *reply* route, not the circulars one. They are different questions with
  // usually different answers — a desk sending circulars through Brevo still replies out of
  // its mailbox — and reading the circulars list here would block the Send button over an
  // SMTP setting a Brevo-routed reply does not need, or offer it when the route a reply
  // actually takes is the unconfigured one.
  const missing = cfg?.replyMissingSettings ?? [];
  const blocked = !cfg?.enabled || missing.length > 0;
  const viaBrevo = cfg?.replyProvider === 'BREVO';

  const send = () => {
    if (!m) return;
    reply.mutate(
      {
        id: m.id,
        body: { to: to.trim(), subject: subject.trim(), bodyHtml: body, footerId, includeOriginal },
      },
      {
        onSuccess: (sent) => {
          toast.success(`Reply sent to ${sent.toAddress}`);
          onClose();
        },
        // The error body carries the server's own words — the provider's refusal, or the
        // list of settings still missing — and they are more use than "sending failed".
        onError: (e: unknown) => {
          const detailMsg =
            (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
          toast.error(detailMsg || 'The reply could not be sent.');
        },
      },
    );
  };

  return (
    <>
      <Modal
        open={open}
        onCancel={onClose}
        width={860}
        destroyOnClose
        title="Reply"
        okText="Send reply"
        okButtonProps={{
          icon: <SendOutlined />,
          disabled: blocked || !to.trim() || !subject.trim() || !body.trim(),
        }}
        confirmLoading={reply.isPending}
        onOk={send}
      >
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          {blocked && (
            <Alert
              type="warning"
              showIcon
              message={
                !cfg?.enabled
                  ? 'Sending is switched off on this server'
                  : viaBrevo
                    ? 'Brevo is not fully configured'
                    : 'The mailbox is not fully configured'
              }
              description={
                cfg?.enabled ? (
                  <>
                    Still needed: {missing.join(', ')}. Until then nothing can go out from
                    here — the Settings tab is where the first few of those live.
                  </>
                ) : (
                  <>
                    MAIL_ENABLED is off, so this app sends nothing at all. You can still write
                    the reply, but the Send button stays disabled.
                  </>
                )
              }
            />
          )}

          <Descriptions size="small" column={1} bordered>
            <Descriptions.Item label="From">
              {cfg ? (
                <Space size={6} wrap>
                  <Typography.Text>{cfg.fromName}</Typography.Text>
                  {/* The mailbox itself, not the circulars From address — those are often
                      different, and a reply has to come back from where the sender wrote. */}
                  <Typography.Text type="secondary">
                    &lt;{cfg.username || cfg.fromAddress}&gt;
                  </Typography.Text>
                  {/* The route is named, not assumed. It is normally the mailbox over SMTP,
                      and where it is not the difference is one the person answering a broker
                      needs to know: the message will not be in the mailbox's Sent folder
                      afterwards, so this box is the only place it was ever seen. */}
                  <Tooltip
                    title={
                      viaBrevo
                        ? "This server cannot open an SMTP connection — its host blocks the port — so replies go out through Brevo instead, still as your mailbox address. The correspondent sees the same From they wrote to, but the message never passes through your mailbox, so no copy is filed in its Sent folder and it may start a new thread rather than joining theirs."
                        : "Replies go out through your mailbox over SMTP, never through Brevo, and from the mailbox's own address rather than the one circulars are sent as — that is the address this message was written to, and the one your replies from Outlook already come from."
                    }
                  >
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {viaBrevo ? `(your mailbox, sent via ${cfg.replyProviderLabel})` : '(your mailbox)'}
                    </Typography.Text>
                  </Tooltip>
                </Space>
              ) : (
                <Typography.Text type="secondary">—</Typography.Text>
              )}
            </Descriptions.Item>
            <Descriptions.Item label="To">
              {/* Editable: a broker who writes from a personal address often wants the
                  answer at the desk one, and only the person reading the thread knows. */}
              <Input value={to} onChange={(e) => setTo(e.target.value)} maxLength={320} />
            </Descriptions.Item>
          </Descriptions>

          <Input
            size="large"
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            maxLength={300}
            placeholder="Subject"
          />

          <RichTextEditor
            value={body}
            onChange={setBody}
            placeholders={placeholdersQ.data}
            minHeight={220}
          />

          <Space wrap>
            <Select<number | null>
              style={{ minWidth: 220 }}
              placeholder="Footer…"
              value={footerId}
              loading={footersQ.isLoading}
              onChange={(v) => setFooterId(v ?? null)}
              options={[
                { value: null as number | null, label: 'No footer' },
                ...(footersQ.data ?? []).map((f) => ({
                  value: f.id as number | null,
                  label: f.replyDefault ? `${f.name} (reply default)` : f.name,
                })),
              ]}
            />
            <Tooltip title="Create and edit footers. The one flagged as the reply default is what this box starts with — it does not have to be the circulars one.">
              <Button icon={<SettingOutlined />} onClick={() => setFootersOpen(true)} />
            </Tooltip>
            <Checkbox
              checked={includeOriginal}
              onChange={(e) => setIncludeOriginal(e.target.checked)}
            >
              Quote the message below
            </Checkbox>
          </Space>

          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            The footer and the quoted message are added when it is sent, so they are not in
            the box above. Placeholders such as {'{{greeting}}'} are filled in from whoever
            this message is linked to.
          </Typography.Text>
        </Space>
      </Modal>

      <FooterManagerModal open={footersOpen} onClose={() => setFootersOpen(false)} />
    </>
  );
}
