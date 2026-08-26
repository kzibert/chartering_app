import { useState } from 'react';
import type { ReactNode } from 'react';
import { Button, theme } from 'antd';
import { DownOutlined, UpOutlined } from '@ant-design/icons';
import { useIsMobile } from '../responsive/useIsMobile';

interface Props {
  /**
   * Something to read while filling the form in — today always the email a cargo or a
   * position is being typed out of. Absent, this component is not there at all: the form
   * renders exactly as it did before, at its own width.
   */
  reference?: ReactNode;
  children: ReactNode;
}

/**
 * A form with the source text it is being copied from beside it.
 *
 * Typing a position list into a form is transcription, and transcription done from memory
 * is transcription done wrong — nobody holds "OPEN SALERNO 01/03 SEPT, LAST CGO CEMENT"
 * across a drawer that covers the email. So the text stays on screen next to the fields,
 * and the fields are filled while reading it.
 *
 * The two layouts differ in more than column count, because the constraint differs. On a
 * desktop there is width to spare and the text simply sits beside the form, scrolling in
 * its own box so a 200-line circular cannot stretch the dialog. On a phone there is no
 * second column to be had, so the text is pinned to the top instead and the form scrolls
 * underneath it — the same two-pane arrangement, turned ninety degrees. It collapses to a
 * single bar, because a phone screen half-full of email is a phone screen with two fields
 * on it, and once the quantity has been read the bar is worth more than the text.
 */
export default function FormWithReference({ reference, children }: Props) {
  const isMobile = useIsMobile();
  const { token } = theme.useToken();
  const [open, setOpen] = useState(true);

  if (!reference) return <>{children}</>;

  if (isMobile) {
    return (
      <>
        {/*
         * Sticky against the dialog's own scroller, so scrolling down to the laycan fields
         * does not take the laycan out of view. z-index because antd's pickers and selects
         * paint their own stacking contexts as you tab through the form underneath.
         */}
        <div
          style={{
            position: 'sticky',
            top: 0,
            zIndex: 3,
            background: token.colorBgContainer,
            borderBottom: `1px solid ${token.colorBorderSecondary}`,
            marginBottom: 12,
            paddingBottom: 8,
          }}
        >
          <Button
            type="text"
            size="small"
            block
            icon={open ? <UpOutlined /> : <DownOutlined />}
            onClick={() => setOpen((v) => !v)}
            style={{ textAlign: 'left', paddingInline: 0 }}
          >
            {open ? 'Hide the email' : 'Show the email'}
          </Button>
          {open && (
            <div style={{ maxHeight: '38vh', overflow: 'auto' }}>{reference}</div>
          )}
        </div>
        {children}
      </>
    );
  }

  return (
    <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
      {/* width: 0 for the same reason the message drawer's title carries it — a flex item
          is min-width:auto by default, and one of these panes holds 60-character report
          ids that would otherwise set the floor for the whole dialog's width. */}
      <div style={{ flex: '3 1 0', width: 0, minWidth: 0 }}>{children}</div>
      <div
        style={{
          flex: '2 1 0',
          width: 0,
          minWidth: 0,
          position: 'sticky',
          top: 0,
          maxHeight: '68vh',
          overflow: 'auto',
        }}
      >
        {reference}
      </div>
    </div>
  );
}
