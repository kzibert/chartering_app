import { App, Button, Space, Tooltip, Typography } from 'antd';
import { CloseOutlined, MailOutlined } from '@ant-design/icons';
import ReplyModal from '../pages/mailbox/ReplyModal';
import ReachOutModal from '../pages/companies/ReachOutModal';
import { useIsMobile } from '../responsive/useIsMobile';
import { useComposer, type Draft } from './store';

/**
 * Where drafts are shown: the one being written as its window, the rest as chips in a dock at
 * the foot of the screen, over whatever page is open. Mounted once, beside the pages rather
 * than inside any of them, which is what lets a draft outlive the drawer it was started from.
 */
export default function ComposerHost() {
  const { drafts, update, minimize, restore, discard } = useComposer();
  const { modal } = App.useApp();
  const isMobile = useIsMobile();

  const open = drafts.find((d) => !d.minimized);
  const docked = drafts.filter((d) => d.minimized);

  const hasContent = (d: Draft) =>
    d.body.trim().length > 0 || (d.kind === 'reach-out' && d.picked.length > 0);

  const discardDocked = (d: Draft) => {
    if (!hasContent(d)) {
      discard(d.id);
      return;
    }
    modal.confirm({
      title: 'Discard this draft?',
      content: `"${d.label}" is not saved anywhere else.`,
      okText: 'Discard',
      okButtonProps: { danger: true },
      cancelText: 'Keep it',
      onOk: () => discard(d.id),
    });
  };

  return (
    <>
      {open?.kind === 'reply' && (
        <ReplyModal
          key={open.id}
          draft={open}
          onChange={(patch) => update(open.id, patch)}
          onMinimize={() => minimize(open.id)}
          onDone={() => discard(open.id)}
        />
      )}
      {open?.kind === 'reach-out' && (
        <ReachOutModal
          key={open.id}
          draft={open}
          onChange={(patch) => update(open.id, patch)}
          onMinimize={() => minimize(open.id)}
          onDone={() => discard(open.id)}
        />
      )}

      {docked.length > 0 && (
        <div
          style={{
            position: 'fixed',
            right: 16,
            // Clear of the phone's bottom tab bar, which is 56px and sits over everything.
            bottom: isMobile ? 'calc(72px + env(safe-area-inset-bottom, 0px))' : 16,
            // Above drawers, which nest upwards from 1000, so a draft can be picked up while a
            // record is open - and under the composer window (1800), which it waits for.
            zIndex: 1700,
            display: 'flex',
            flexDirection: isMobile ? 'column' : 'row-reverse',
            alignItems: 'flex-end',
            gap: 8,
            maxWidth: 'calc(100vw - 32px)',
            flexWrap: 'wrap',
          }}
        >
          {docked.map((d) => (
            <div
              key={d.id}
              style={{
                background: '#fff',
                border: '1px solid rgba(5,5,5,0.12)',
                borderRadius: 8,
                boxShadow: '0 6px 16px rgba(0,0,0,0.12)',
                padding: '4px 4px 4px 12px',
                maxWidth: 280,
              }}
            >
              <Space size={4}>
                <Tooltip title="Open the draft where you left it">
                  <Typography.Link
                    onClick={() => restore(d.id)}
                    ellipsis
                    style={{ maxWidth: 200, display: 'inline-block' }}
                  >
                    <MailOutlined style={{ marginRight: 6 }} />
                    {d.label || (d.kind === 'reply' ? 'Reply' : 'New message')}
                  </Typography.Link>
                </Tooltip>
                <Tooltip title="Discard this draft">
                  <Button
                    size="small"
                    type="text"
                    icon={<CloseOutlined />}
                    aria-label="Discard draft"
                    onClick={() => discardDocked(d)}
                  />
                </Tooltip>
              </Space>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
