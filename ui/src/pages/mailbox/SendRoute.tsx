import { useEffect, useState } from 'react';
import { Alert, Button, Select, Space, Tooltip, Typography } from 'antd';
import { SettingOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { campaignsApi } from '../../api/campaigns';
import { emailFootersApi } from '../../api/emailLibrary';
import FooterManagerModal from '../circulars/FooterManagerModal';

/**
 * What the two one-to-one composers share — answering a message in the Mailbox, and writing to
 * a firm from its record. Both leave by the same route, from the same address, with the same
 * footer library, so the parts that say which are one set of components rather than two copies
 * free to drift.
 */

/**
 * Whether anything can go out, asked of the *reply* route rather than the circulars one.
 *
 * They are different questions with usually different answers — a desk sending circulars
 * through Brevo still writes one-to-one out of its mailbox — and reading the circulars list
 * would block Send over an SMTP setting a Brevo-routed message does not need, or offer it when
 * the route actually taken is the unconfigured one.
 */
export function useSendRoute() {
  const cfgQ = useQuery({ queryKey: ['campaign', 'config'], queryFn: campaignsApi.config });
  const cfg = cfgQ.data;
  const missing = cfg?.replyMissingSettings ?? [];
  return {
    cfg,
    missing,
    blocked: !cfg?.enabled || missing.length > 0,
    viaBrevo: cfg?.replyProvider === 'BREVO',
  };
}

type Route = ReturnType<typeof useSendRoute>;

/** Why Send is disabled, in words that say where to go and fix it. Nothing when it is not. */
export function SendBlockedAlert({ route, what }: { route: Route; what: 'reply' | 'message' }) {
  const { cfg, missing, blocked, viaBrevo } = route;
  if (!blocked) return null;
  return (
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
            Still needed: {missing.join(', ')}. Until then nothing can go out from here — the
            Settings tab is where the first few of those live.
          </>
        ) : (
          <>
            MAIL_ENABLED is off, so this app sends nothing at all. You can still write the{' '}
            {what}, but the Send button stays disabled.
          </>
        )
      }
    />
  );
}

/**
 * Who it comes from, and by which route — named rather than assumed. It is normally the
 * mailbox over SMTP, and where it is not the difference is one the writer needs to know: the
 * message will not be in the mailbox's Sent folder afterwards, so this box is the only place
 * it was ever seen.
 */
export function FromLine({ route }: { route: Route }) {
  const { cfg, viaBrevo } = route;
  if (!cfg) return <Typography.Text type="secondary">—</Typography.Text>;
  return (
    <Space size={6} wrap>
      <Typography.Text>{cfg.fromName}</Typography.Text>
      {/* The mailbox itself, not the circulars From address — those are often different, and
          one-to-one mail has to come from where correspondents write to. */}
      <Typography.Text type="secondary">&lt;{cfg.username || cfg.fromAddress}&gt;</Typography.Text>
      <Tooltip
        title={
          viaBrevo
            ? "This server cannot open an SMTP connection — its host blocks the port — so this goes out through Brevo instead, still as your mailbox address. The recipient sees the same From, but the message never passes through your mailbox, so no copy is filed in its Sent folder."
            : "This goes out through your mailbox over SMTP, never through Brevo, and from the mailbox's own address rather than the one circulars are sent as — the address your mail from Outlook already comes from."
        }
      >
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {viaBrevo ? `(your mailbox, sent via ${cfg.replyProviderLabel})` : '(your mailbox)'}
        </Typography.Text>
      </Tooltip>
    </Space>
  );
}

/**
 * The footer, starting on the one flagged as the reply default, with the library a click away.
 *
 * The default is applied when the list arrives and only while nothing is chosen, so it cannot
 * overwrite a footer picked a second earlier; `resetKey` changing (a new message being written)
 * clears the choice and lets it apply again.
 */
export function FooterPicker({
  open,
  resetKey,
  value,
  onChange,
}: {
  open: boolean;
  resetKey: unknown;
  value: number | null;
  onChange: (id: number | null) => void;
}) {
  const [managing, setManaging] = useState(false);
  const [applied, setApplied] = useState(false);
  const footersQ = useQuery({ queryKey: ['email-footers'], queryFn: emailFootersApi.list, enabled: open });

  useEffect(() => setApplied(false), [resetKey]);
  useEffect(() => {
    if (!open || applied || !footersQ.data) return;
    const def = footersQ.data.find((f) => f.replyDefault);
    if (def && value == null) onChange(def.id);
    setApplied(true);
  }, [open, applied, footersQ.data]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <Space wrap>
        <Select<number | null>
          style={{ minWidth: 220 }}
          placeholder="Footer…"
          value={value}
          loading={footersQ.isLoading}
          onChange={(v) => onChange(v ?? null)}
          options={[
            { value: null as number | null, label: 'No footer' },
            ...(footersQ.data ?? []).map((f) => ({
              value: f.id as number | null,
              label: f.replyDefault ? `${f.name} (reply default)` : f.name,
            })),
          ]}
        />
        <Tooltip title="Create and edit footers. The one flagged as the reply default is what this box starts with — it does not have to be the circulars one.">
          <Button icon={<SettingOutlined />} onClick={() => setManaging(true)} />
        </Tooltip>
      </Space>
      <FooterManagerModal open={managing} onClose={() => setManaging(false)} />
    </>
  );
}
