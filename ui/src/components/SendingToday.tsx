import { Alert, Progress, Space, Statistic, Tag, Tooltip, Typography } from 'antd';
import { CloudOutlined, MailOutlined, SendOutlined } from '@ant-design/icons';
import type { CirculationToday } from '../api/types';

/**
 * Today's outgoing volume, split by the flow each message left through.
 *
 * Shared by the Circulars and Settings tabs so the two cannot drift into quoting different
 * numbers for the same day — the compact form is a row of tags for a card header, the full
 * form is a panel. Both read the same query.
 *
 * The two halves are sourced differently and that is not an accident: SMTP offers no way to
 * ask a mailbox what it has already sent, so the mailbox figure can only be counted locally,
 * while Brevo's allowance is spent by everything on the account and so has to be read from
 * Brevo. The UI says which is which rather than presenting them as one number.
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

      <Tooltip title="Sent from your own mailbox over SMTP today. Nothing can report your mailbox's own daily cap, so check this against it yourself.">
        <Tag icon={<SendOutlined />} color="blue" style={{ marginInlineEnd: 0 }}>
          {today.viaMailbox} mailbox
        </Tag>
      </Tooltip>

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
        <Statistic title="Sent today (this app)" value={today.sent} prefix={<MailOutlined />} />
        <Statistic
          title="From your mailbox"
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
