import { useState } from 'react';
import { Spin, Typography } from 'antd';

/**
 * The body itself.
 *
 * <p>HTML mail is rendered inside a sandboxed iframe rather than into the page. The markup
 * is already sanitized server-side, so this is not the security boundary — it is the style
 * boundary. A circular from a broker carries its own CSS, frequently including rules on
 * {@code body} and {@code table}, and dropping that straight into the document would
 * restyle the app around it. The sandbox attribute is empty, which also leaves scripting off
 * as a second line behind the sanitizer.
 */
export default function MessageBody({
  html,
  text,
  height = '60vh',
}: {
  html?: string;
  text?: string;
  /** How tall the iframe is. The reading drawer wants most of a screen; the panel beside a
      form being filled in from this message wants a good deal less. */
  height?: string;
}) {
  // A long Outlook reply chain is a hundred kilobytes of nested tables and takes a second
  // or two to lay out. Without this the reader stares at an empty white box in the meantime
  // and reasonably concludes the message has no body.
  const [rendered, setRendered] = useState(false);

  if (html) {
    return (
      <div style={{ position: 'relative' }}>
        {!rendered && (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: '#fff',
              border: '1px solid rgba(5,5,5,0.06)',
              borderRadius: 6,
              zIndex: 1,
            }}
          >
            <Spin tip="Rendering the message…">
              <div style={{ padding: 24 }} />
            </Spin>
          </div>
        )}
      <iframe
        onLoad={() => setRendered(true)}
        title="Message body"
        sandbox=""
        srcDoc={`<!doctype html><meta charset="utf-8">
          <style>
            body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; font-size: 14px;
                   color: #262626; margin: 0; padding: 4px; word-break: break-word; }
            img { max-width: 100%; height: auto; }
            table { max-width: 100%; }
          </style>${html}`}
        style={{
          width: '100%',
          height,
          border: '1px solid rgba(5,5,5,0.06)',
          borderRadius: 6,
          background: '#fff',
        }}
      />
      </div>
    );
  }
  return (
    <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', margin: 0 }}>
      {text || <Typography.Text type="secondary">This message has no readable body.</Typography.Text>}
    </Typography.Paragraph>
  );
}
