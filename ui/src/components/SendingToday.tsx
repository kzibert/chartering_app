import { Alert, Progress, Space, Statistic, Tag, Tooltip, Typography } from 'antd';
import { CloudOutlined, MailOutlined, MessageOutlined, SendOutlined } from '@ant-design/icons';
import type { CirculationToday } from '../api/types';

/**
 * Today's outgoing volume, split by the flow each message left through.
 *
 * Shared by the Circulars and Settings tabs so the two cannot drift into quoting different
 * numbers for the same day — the compact form is a row of tags for a card header, the full
 * form is a panel. Both read the same query.
 *
 * The parts are sourced differently and that is not an accident. What this app sent through
 * SMTP it counts itself, because SMTP offers no way to ask a mailbox what it has already
 * sent. Brevo's allowance is spent by everything on the account, so that half has to be read
 * from Brevo. And what the mailbox has sent *without* this app — a reply typed in Outlook,
 * in the webmail, on a phone — is read out of the Sent folder the mailbox sync already
 * mirrors, which is the only place it appears at all.
 *
 * Those last two overlap on purpose and must never be added: the provider files this app's
 * own SMTP circulars into that same Sent folder, so "mailbox today" already contains most of
 * "via SMTP" — by an amount only the provider knows. The UI says which is which rather than
 * presenting them as one number.
 *
 * Brevo's own two figures are likewise kept apart. `remaining` is live and exact; `sent` is an
 * aggregated report that counts accepted messages — including suppressed ones that cost no
 * allowance — and lags a running campaign by minutes. They are shown side by side and never
 * added or subtracted from one another.
 */

/** Amber once the day is mostly spent, red at the point where a circulation would be cut off. */
function quotaColour(used: number, limit: number): string {
  const ratio = limit > 0 ? Math.min(used / limit, 1) : 0;
  if (ratio >= 0.9) return '#cf1322';
  if (ratio >= 0.7) return '#d46b08';
  return '#389e0d';
}

/**
 * What the account has spent today, from the only pair that can be subtracted: the ceiling and
 * Brevo's live remainder. Clamped at zero because the ceiling is configured and the remainder
 * is Brevo's — a plan upgrade that has not reached `BREVO_DAILY_LIMIT` yet should read as a
 * full day, not a negative one.
 */
function usedToday(limit: number, remaining: number): number {
  return Math.max(0, Math.min(limit, limit - remaining));
}

/** How long ago the Sent folder was read, in words a tooltip can end a sentence with. */
function syncedAgo(at?: string): string {
  if (!at) return 'not yet synced';
  const minutes = Math.max(0, Math.round((Date.now() - new Date(at).getTime()) / 60000));
  if (minutes < 1) return 'synced just now';
  if (minutes < 60) return `synced ${minutes} minute${minutes === 1 ? '' : 's'} ago`;
  const hours = Math.round(minutes / 60);
  return `synced ${hours} hour${hours === 1 ? '' : 's'} ago`;
}

export function SendingTodayTags({ today }: { today?: CirculationToday }) {
  if (!today) {
    return (
      <Tag icon={<MailOutlined />} style={{ marginInlineEnd: 0 }}>
        — sent today
      </Tag>
    );
  }

  const brevo = today.brevo;
  const remaining = brevo?.remaining;
  const limit = brevo?.dailyLimit;
  const brevoSent = brevo?.sent;

  return (
    <>
      <Tooltip
        title={
          <>
            {today.sent} circular email{today.sent === 1 ? '' : 's'} sent by this app since
            midnight ({today.date}), over {today.circulations} circulation
            {today.circulations === 1 ? '' : 's'} — {today.viaMailbox} from the mailbox and{' '}
            {today.viaBrevo} through Brevo. Counted in the server's local time.
          </>
        }
      >
        <Tag
          icon={<MailOutlined />}
          color={today.sent ? 'green' : 'default'}
          style={{ marginInlineEnd: 0 }}
        >
          {today.sent} sent today
        </Tag>
      </Tooltip>

      {/* What the mailbox itself has sent, which is the figure to read against its daily
          cap — it counts the replies and the mail this app never saw. It replaces the
          app-only SMTP tag rather than joining it: the two overlap, and the app-only number
          is still one sentence away, in the tooltip above. Where there is no Sent folder to
          read, the old tag is what is left to show. */}
      {today.mailbox?.sent != null ? (
        <Tooltip
          title={
            <>
              {today.mailbox.sent} message{today.mailbox.sent === 1 ? '' : 's'} in your
              mailbox's {today.mailbox.sentFolder ?? 'Sent'} folder today, whoever sent them —
              this app's {today.viaMailbox} SMTP circular
              {today.viaMailbox === 1 ? '' : 's'}, the {today.mailbox.replies} repl
              {today.mailbox.replies === 1 ? 'y' : 'ies'} sent from here, and anything you
              wrote in Outlook or the webmail. Read from the folder, so it is only as current
              as the sync: {syncedAgo(today.mailbox.folderSyncedAt)}. Nothing can report your
              mailbox's own daily cap, so check this against it yourself.
            </>
          }
        >
          <Tag icon={<SendOutlined />} color="blue" style={{ marginInlineEnd: 0 }}>
            {today.mailbox.sent} mailbox
          </Tag>
        </Tooltip>
      ) : (
        <Tooltip title="Sent from your own mailbox over SMTP today, counted by this app. Your mailbox reports no Sent folder, so replies written elsewhere cannot be counted here. Nothing can report your mailbox's own daily cap either, so check this against it yourself.">
          <Tag icon={<SendOutlined />} color="blue" style={{ marginInlineEnd: 0 }}>
            {today.viaMailbox} mailbox
          </Tag>
        </Tooltip>
      )}

      {/* This app's own replies, exact from the moment they leave — the mailbox figure
          beside it only learns about them at the next sync. Shown whenever there are any,
          and the tooltip is what says the two overlap rather than stack. */}
      {today.mailbox != null && today.mailbox.replies > 0 && (
        <Tooltip title="Replies sent from this app today, counted as they went out. They are inside the mailbox figure as well, once that folder syncs.">
          <Tag icon={<MessageOutlined />} color="cyan" style={{ marginInlineEnd: 0 }}>
            {today.mailbox.replies} replied
          </Tag>
        </Tooltip>
      )}

      {brevo && (
        <Tooltip
          title={
            brevo.error ? (
              <>Brevo's own figures are unavailable: {brevo.error}</>
            ) : (
              <>
                {today.viaBrevo} sent through Brevo by this app today.{' '}
                {remaining != null && limit != null ? (
                  <>
                    Brevo has {remaining} of the day's {limit} left — that allowance is spent by
                    everything on the account, including anything sent from Brevo's own
                    dashboard or another integration, and it is what the cap is enforced
                    against.{' '}
                  </>
                ) : (
                  'This plan has no daily send ceiling to report. '
                )}
                {brevoSent != null && (
                  <>Brevo's report for today lists {brevoSent} accepted account-wide.</>
                )}
              </>
            )
          }
        >
          <Tag
            icon={<CloudOutlined />}
            color={brevo.error ? 'default' : 'purple'}
            style={{ marginInlineEnd: 0 }}
          >
            {today.viaBrevo} Brevo
            {remaining != null && limit != null && ` · ${remaining}/${limit} left`}
          </Tag>
        </Tooltip>
      )}
    </>
  );
}

export function SendingTodayPanel({ today }: { today?: CirculationToday }) {
  if (!today) return null;

  const brevo = today.brevo;
  const brevoSent = brevo?.sent;
  const blocked = brevo?.blocked;
  const remaining = brevo?.remaining;
  const limit = brevo?.dailyLimit;
  const showQuota = remaining != null && limit != null && limit > 0;
  const used = showQuota ? usedToday(limit, remaining) : 0;

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Space size="large" wrap>
        <Statistic title="Circulars sent today" value={today.sent} prefix={<MailOutlined />} />
        <Statistic
          title="Of those, over SMTP"
          value={today.viaMailbox}
          prefix={<SendOutlined />}
          valueStyle={{ color: '#1677ff' }}
        />
        <Statistic
          title="Through Brevo"
          value={today.viaBrevo}
          prefix={<CloudOutlined />}
          valueStyle={{ color: '#722ed1' }}
        />
        {showQuota && (
          <Statistic
            title="Brevo left today"
            value={remaining}
            suffix={`/ ${limit}`}
            valueStyle={{ color: quotaColour(used, limit) }}
          />
        )}
      </Space>

      {/* The mailbox's own day, kept in a row of its own rather than beside the circular
          figures: it is a different question with a different source, and standing them in
          one line invites exactly the addition the paragraph under it warns against. */}
      {today.mailbox && (
        <div>
          <Space size="large" wrap>
            <Statistic
              title="Your mailbox today"
              value={today.mailbox.sent ?? '—'}
              prefix={<SendOutlined />}
              valueStyle={{ color: '#1677ff' }}
            />
            <Statistic
              title="Replies from this app"
              value={today.mailbox.replies}
              prefix={<MessageOutlined />}
              valueStyle={{ color: '#08979c' }}
            />
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
            {today.mailbox.sent != null ? (
              <>
                Everything your mailbox sent today, read out of its{' '}
                <b>{today.mailbox.sentFolder ?? 'Sent'}</b> folder — the circulars this app
                sent over SMTP, the replies sent from here, and whatever you wrote in Outlook,
                the webmail or on your phone. It is only as current as the mailbox sync
                ({syncedAgo(today.mailbox.folderSyncedAt)}), and it already contains the two
                figures above it, so it is the one to read against your provider's daily cap —
                never the sum. The replies count beside it is the exact one: it is written as
                each reply goes out, and folds into the folder figure at the next sync.
              </>
            ) : (
              <>
                Your mailbox reports no Sent folder over IMAP, so what it sent outside this app
                cannot be counted — a reply written in Outlook is invisible here. The replies
                figure is this app's own, and is exact.
              </>
            )}
          </Typography.Text>
        </div>
      )}

      {showQuota && (
        <div>
          <Progress
            percent={Math.round((used / limit) * 100)}
            strokeColor={quotaColour(used, limit)}
            format={(p) => `${p}% of Brevo's day used`}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Brevo has {remaining} of today's {limit} left, so {used} of the allowance is spent —
            account-wide, which is more than this app's {today.viaBrevo} whenever something else
            has sent as well: a campaign from Brevo's dashboard, another integration, a test
            from their UI. It is Brevo's remainder, not this app's count, that the cap is
            enforced against. The {limit} is the plan's ceiling as configured in{' '}
            <Typography.Text code>BREVO_DAILY_LIMIT</Typography.Text> — Brevo publishes only the
            remainder, never the allowance, so a wrong plan here means a wrong denominator.
          </Typography.Text>
          {brevoSent != null && (
            <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>
              Separately, Brevo's report for today lists {brevoSent} message
              {brevoSent === 1 ? '' : 's'} accepted on the account
              {blocked ? (
                <>
                  , {blocked} of which {blocked === 1 ? 'was' : 'were'} blocked and so cost no
                  allowance
                </>
              ) : null}
              . It is compiled after the fact and trails a running campaign by a few minutes, so
              it will not always match the {used} above — the remainder is the figure to trust.
            </Typography.Text>
          )}
        </div>
      )}

      {brevo?.error && (
        <Alert
          type="warning"
          showIcon
          message="Brevo's figures are unavailable"
          description={
            <>
              {brevo.error}. The mailbox count above is unaffected — it is counted here, not
              asked of anyone.
            </>
          }
        />
      )}

      {!brevo && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          No Brevo API key is configured, so there is no account to report on. Set{' '}
          <Typography.Text code>BREVO_API_KEY</Typography.Text> in{' '}
          <Typography.Text code>.env</Typography.Text> to see the day's allowance here.
        </Typography.Text>
      )}

      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        Counted in the server's local day ({today.date}) from each address's own send time, so a
        circulation resumed this morning counts against today rather than the day it started.
        Nothing can report your own mailbox's daily cap — that half of the figure is for you to
        read against what your provider allows.
      </Typography.Text>
    </Space>
  );
}
