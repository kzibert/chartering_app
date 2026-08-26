import { Typography, theme } from 'antd';
import dayjs from 'dayjs';
import MessageBody from './MessageBody';
import type { MailMessageDetail } from '../../api/types';

/**
 * The email, shown beside a form being filled in from it.
 *
 * **Plain text wherever there is any**, and the HTML render only as a fallback. Both parts
 * carry the same words, but this pane is read for figures — "ABT 28/35,000 DWT", "01/03
 * SEPT" — and a position list's own alignment is part of how those figures read. Rendering
 * it as a block of preformatted text keeps that, keeps it selectable and copyable into the
 * fields beside it, and avoids putting a second sandboxed iframe on the screen for a
 * message the drawer underneath is already rendering. The sync extracts text even from an
 * HTML-only message, so the fallback is rare — and it exists because "rare" is not "never".
 *
 * The header repeats what the drawer behind it already says, because by the time this is
 * open the drawer is behind a dialog: who sent it and when is exactly what decides the
 * "reported by" and "reported at" the form is asking for.
 */
export default function MailReference({ detail }: { detail: MailMessageDetail }) {
  const { token } = theme.useToken();
  const m = detail.message;

  return (
    <div
      style={{
        border: `1px solid ${token.colorBorderSecondary}`,
        borderRadius: token.borderRadiusLG,
        background: token.colorFillQuaternary,
        padding: 12,
      }}
    >
      <Typography.Text strong style={{ display: 'block', overflowWrap: 'anywhere' }}>
        {m.subject || '(no subject)'}
      </Typography.Text>
      <Typography.Text type="secondary" style={{ fontSize: 12, overflowWrap: 'anywhere' }}>
        {m.fromName || m.fromAddress} · {dayjs(m.receivedAt).format('YYYY-MM-DD HH:mm')}
      </Typography.Text>

      {detail.bodyText ? (
        /* Its own horizontal scroller, not a wrap: a position list is columns of ship,
           size, port and dates, and re-wrapping it at 300px turns those columns into
           prose. See `.responsive-scroll-x` — this is exactly the case it is for. */
        <pre
          className="responsive-scroll-x"
          style={{
            margin: '8px 0 0',
            fontSize: 12,
            lineHeight: 1.45,
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
            color: token.colorText,
          }}
        >
          {detail.bodyText}
        </pre>
      ) : (
        <div style={{ marginTop: 8 }}>
          <MessageBody html={detail.bodyHtml} text={detail.bodyText} height="40vh" />
        </div>
      )}
    </div>
  );
}
