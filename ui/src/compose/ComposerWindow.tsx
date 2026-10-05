import type { ReactNode } from 'react';
import { App, Button, Modal, Tooltip } from 'antd';
import { MinusOutlined } from '@ant-design/icons';

/**
 * The window a draft is written in, with a way out that is not "throw it away".
 *
 * <p>Minimise hides it to the dock with everything kept; a click outside does nothing. The
 * close button and Escape discard it — after asking, when anything has been written, because a
 * paragraph lost to a stray key is the failure this whole arrangement exists to prevent.
 */
export default function ComposerWindow({
  title,
  dirty,
  onMinimize,
  onDiscard,
  footer,
  children,
}: {
  title: ReactNode;
  /** Whether closing would lose something the writer typed or picked. */
  dirty: boolean;
  onMinimize: () => void;
  onDiscard: () => void;
  footer: ReactNode;
  children: ReactNode;
}) {
  const { modal } = App.useApp();

  const close = () => {
    if (!dirty) {
      onDiscard();
      return;
    }
    modal.confirm({
      title: 'Discard this draft?',
      content: 'What you have written is not saved anywhere else. Minimise it instead to come back to it later.',
      okText: 'Discard',
      okButtonProps: { danger: true },
      cancelText: 'Keep writing',
      onOk: onDiscard,
    });
  };

  return (
    <Modal
      open
      width={860}
      // Explicit, because this window is mounted at the app root and not inside the drawer it
      // was opened from: left at antd's default it is a 1000 among drawers that nest upwards
      // from 1000 in steps of 100, so Reach out from a company opened off a vessel landed
      // under both. 1800 clears any stack of drawers this app opens and stays under antd's
      // 2000 ceiling, where its own confirm dialogs sit - so "Discard this draft?" still shows
      // above it, and the selects and tooltips inside it are raised from here by antd itself.
      zIndex={1800}
      onCancel={close}
      // Stepping outside the window to look something up is the whole point; it must not cost
      // the draft.
      maskClosable={false}
      footer={footer}
      title={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingRight: 32 }}>
          <div style={{ flex: 1, minWidth: 0 }}>{title}</div>
          <Tooltip title="Minimise — keep the draft and carry on in the app. It waits at the bottom of the screen.">
            <Button size="small" type="text" icon={<MinusOutlined />} onClick={onMinimize} aria-label="Minimise" />
          </Tooltip>
        </div>
      }
    >
      {children}
    </Modal>
  );
}
