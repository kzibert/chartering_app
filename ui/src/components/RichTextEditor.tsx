import { useEffect, useRef, useState } from 'react';
import { Button, Divider, Dropdown, Input, Space, Tooltip, theme } from 'antd';
import {
  BoldOutlined,
  ItalicOutlined,
  UnderlineOutlined,
  UnorderedListOutlined,
  OrderedListOutlined,
  LinkOutlined,
  ClearOutlined,
  TagOutlined,
  CodeOutlined,
} from '@ant-design/icons';

interface Props {
  value: string;
  onChange: (html: string) => void;
  /** Placeholder token -> description, rendered as an insert menu. */
  placeholders?: Record<string, string>;
  disabled?: boolean;
  minHeight?: number;
}

/**
 * Small contentEditable editor producing the HTML that goes into the circular, with an
 * HTML source view for pasting hand-written markup (a designed footer, a table, a link
 * block) that the WYSIWYG surface can't express.
 *
 * Deliberately not a full editor library: email HTML wants to stay simple. Rich markup
 * pasted out of Word/Outlook is the usual cause of mail rendering badly and scoring
 * poorly with spam filters, so paste into the visual surface is forced to plain text —
 * switch to the source view when you genuinely mean to paste HTML.
 */
export default function RichTextEditor({
  value,
  onChange,
  placeholders,
  disabled,
  minHeight = 260,
}: Props) {
  const ref = useRef<HTMLDivElement>(null);
  const { token } = theme.useToken();
  const [sourceMode, setSourceMode] = useState(false);

  // Seed the DOM once, and re-sync only when the prop diverges while unfocused (e.g.
  // loading a template). Writing innerHTML on every render would kill the caret mid-typing.
  // sourceMode is a dependency because leaving the source view remounts an empty div that
  // has to be refilled — without it, flipping back would show a blank editor.
  useEffect(() => {
    const el = ref.current;
    if (!el || sourceMode) return;
    if (document.activeElement !== el && el.innerHTML !== value) {
      el.innerHTML = value;
    }
  }, [value, sourceMode]);

  useEffect(() => {
    // Emit <b>/<i> tags rather than CSS-styled spans — far better supported by mail clients.
    try {
      document.execCommand('styleWithCSS', false, 'false');
    } catch {
      /* not supported — tags are the default anyway */
    }
  }, []);

  const emit = () => onChange(ref.current?.innerHTML ?? '');

  const exec = (command: string, arg?: string) => {
    ref.current?.focus();
    document.execCommand(command, false, arg);
    emit();
  };

  const insertLink = () => {
    const url = window.prompt('Link URL', 'https://');
    if (url) exec('createLink', url);
  };

  const insertPlaceholder = (key: string) => exec('insertText', `{{${key}}}`);

  const onPaste = (e: React.ClipboardEvent<HTMLDivElement>) => {
    e.preventDefault();
    const text = e.clipboardData.getData('text/plain');
    document.execCommand('insertText', false, text);
    emit();
  };

  // keepFocus: toolbar buttons must not steal the selection, or the command applies to nothing.
  const keepFocus = (e: React.MouseEvent) => e.preventDefault();

  // Formatting commands act on the contentEditable selection, so they're meaningless
  // while the source view is showing raw markup.
  const tool = (title: string, icon: React.ReactNode, onClick: () => void) => (
    <Tooltip title={title}>
      <Button
        size="small"
        type="text"
        icon={icon}
        disabled={disabled || sourceMode}
        onMouseDown={keepFocus}
        onClick={onClick}
      />
    </Tooltip>
  );

  return (
    <div style={{ border: `1px solid ${token.colorBorder}`, borderRadius: token.borderRadius }}>
      <div
        style={{
          borderBottom: `1px solid ${token.colorBorderSecondary}`,
          padding: '4px 8px',
          background: token.colorFillQuaternary,
        }}
      >
        <Space size={2} wrap>
          {tool('Bold', <BoldOutlined />, () => exec('bold'))}
          {tool('Italic', <ItalicOutlined />, () => exec('italic'))}
          {tool('Underline', <UnderlineOutlined />, () => exec('underline'))}
          <Divider type="vertical" style={{ margin: '0 4px' }} />
          {tool('Bullet list', <UnorderedListOutlined />, () => exec('insertUnorderedList'))}
          {tool('Numbered list', <OrderedListOutlined />, () => exec('insertOrderedList'))}
          {tool('Link', <LinkOutlined />, insertLink)}
          {tool('Clear formatting', <ClearOutlined />, () => exec('removeFormat'))}
          {placeholders && Object.keys(placeholders).length > 0 && (
            <>
              <Divider type="vertical" style={{ margin: '0 4px' }} />
              <Dropdown
                disabled={disabled}
                menu={{
                  items: Object.entries(placeholders).map(([key, desc]) => ({
                    key,
                    label: (
                      <span>
                        <code>{`{{${key}}}`}</code>
                        <span style={{ color: token.colorTextSecondary, marginInlineStart: 8, fontSize: 12 }}>
                          {desc}
                        </span>
                      </span>
                    ),
                  })),
                  onClick: ({ key }) => insertPlaceholder(key),
                }}
              >
                <Button
                  size="small"
                  type="text"
                  icon={<TagOutlined />}
                  disabled={disabled || sourceMode}
                  onMouseDown={keepFocus}
                >
                  Insert field
                </Button>
              </Dropdown>
            </>
          )}
          <Divider type="vertical" style={{ margin: '0 4px' }} />
          <Tooltip title={sourceMode ? 'Back to visual editing' : 'Edit HTML source — paste custom markup here'}>
            <Button
              size="small"
              type={sourceMode ? 'primary' : 'text'}
              icon={<CodeOutlined />}
              disabled={disabled}
              onMouseDown={keepFocus}
              onClick={() => setSourceMode((s) => !s)}
            >
              HTML
            </Button>
          </Tooltip>
        </Space>
      </div>
      {sourceMode ? (
        <Input.TextArea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          disabled={disabled}
          autoSize={{ minRows: Math.round(minHeight / 22), maxRows: 30 }}
          spellCheck={false}
          style={{
            border: 'none',
            borderRadius: 0,
            fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace',
            fontSize: 12.5,
          }}
        />
      ) : (
        <div
          ref={ref}
          contentEditable={!disabled}
          suppressContentEditableWarning
          onInput={emit}
          onBlur={emit}
          onPaste={onPaste}
          style={{
            minHeight,
            padding: 12,
            outline: 'none',
            overflowY: 'auto',
            background: disabled ? token.colorFillQuaternary : undefined,
            cursor: disabled ? 'not-allowed' : 'text',
          }}
        />
      )}
    </div>
  );
}
