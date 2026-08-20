import { useState } from 'react';
import {
  App,
  Button,
  Descriptions,
  Drawer,
  Dropdown,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  BankOutlined,
  DisconnectOutlined,
  FolderOutlined,
  InboxOutlined,
  LinkOutlined,
  MailOutlined,
  PaperClipOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useMailFolders, useMailMessage, useMailMessageMutations } from '../../mailbox/store';
import LinkCompanyModal from './LinkCompanyModal';

interface Props {
  messageId?: number;
  onClose: () => void;
  /** Jump to the company drawer, which the page owns — a drawer inside a drawer is a maze. */
  onOpenCompany: (companyId: number) => void;
}

/**
 * One message, opened for reading.
 *
 * <p>Opening it marks it read (the server's default), which is why the drawer offers "Mark
 * unread" rather than a toggle: by the time you can see the button the message has already
 * been read, and the only useful action left is putting it back.
 */
export default function MessageDrawer({ messageId, onClose, onOpenCompany }: Props) {
  const { message: toast } = App.useApp();
  const { data, isLoading } = useMailMessage(messageId);
  const folders = useMailFolders();
  const { setRead, move, unlink } = useMailMessageMutations();
  const [linkOpen, setLinkOpen] = useState(false);

  const m = data?.message;
  const named = (folders.data ?? []).filter((f) => f.id != null);

  const moveMenu = {
    items: [
      { key: 'inbox', icon: <InboxOutlined />, label: 'Back to Inbox' },
      ...(named.length ? [{ type: 'divider' as const }] : []),
      ...named.map((f) => ({ key: String(f.id), icon: <FolderOutlined />, label: f.name })),
    ],
    onClick: ({ key }: { key: string }) => {
      const folderId = key === 'inbox' ? undefined : Number(key);
      move.mutate(
        { id: m!.id, folderId },
        {
          onSuccess: () =>
            toast.success(
              key === 'inbox'
                ? 'Moved back to the Inbox'
                : `Filed into ${named.find((f) => f.id === Number(key))?.name}`,
            ),
        },
      );
    },
  };

  return (
    <>
      <Drawer
        open={messageId != null}
        onClose={onClose}
        width={860}
        destroyOnClose
        title={
          <Space size={8}>
            <MailOutlined />
            <Typography.Text strong ellipsis style={{ maxWidth: 640 }}>
              {m?.subject || '(no subject)'}
            </Typography.Text>
          </Space>
        }
        extra={
          m && (
            <Space wrap>
              <Button
                size="small"
                onClick={() =>
                  setRead.mutate(
                    { id: m.id, read: false },
                    { onSuccess: () => toast.success('Marked unread') },
                  )
                }
              >
                Mark unread
              </Button>
              <Dropdown menu={moveMenu} disabled={named.length === 0}>
                <Button size="small" icon={<FolderOutlined />}>
                  Move to…
                </Button>
              </Dropdown>
              {m.companyId ? (
                <Space.Compact>
                  <Button
                    size="small"
                    icon={<BankOutlined />}
                    onClick={() => onOpenCompany(m.companyId!)}
                  >
                    {m.companyName}
                  </Button>
                  <Tooltip title="Detach from this company">
                    <Button
                      size="small"
                      icon={<DisconnectOutlined />}
                      onClick={() =>
                        unlink.mutate(m.id, { onSuccess: () => toast.success('Link removed') })
                      }
                    />
                  </Tooltip>
                </Space.Compact>
              ) : (
                <Button size="small" icon={<LinkOutlined />} onClick={() => setLinkOpen(true)}>
                  Link to company
                </Button>
              )}
            </Space>
          )
        }
      >
        {isLoading || !m || !data ? (
          <Spin />
        ) : (
          <Space direction="vertical" size="middle" style={{ width: '100%' }}>
            <Descriptions size="small" column={1} bordered>
              <Descriptions.Item label="From">
                <Space size={6} wrap>
                  <Typography.Text strong>{m.fromName || m.fromAddress}</Typography.Text>
                  {m.fromName && <Typography.Text copyable>{m.fromAddress}</Typography.Text>}
                  {!m.companyId && (
                    <Tooltip title="This address matches no contact, so the message is not attached to any company">
                      <Tag>unknown sender</Tag>
                    </Tooltip>
                  )}
                </Space>
              </Descriptions.Item>
              {data.toAddresses && (
                <Descriptions.Item label="To">{data.toAddresses}</Descriptions.Item>
              )}
              {data.ccAddresses && (
                <Descriptions.Item label="Cc">{data.ccAddresses}</Descriptions.Item>
              )}
              <Descriptions.Item label="Received">
                <Space size={8} wrap>
                  <span>{dayjs(m.receivedAt).format('YYYY-MM-DD HH:mm')}</span>
                  {m.folderName ? (
                    <Tag color={m.filedByRuleId ? 'geekblue' : 'default'} icon={<FolderOutlined />}>
                      {m.folderName}
                      {m.filedByRuleId ? ' (by rule)' : ''}
                    </Tag>
                  ) : (
                    <Tag icon={<InboxOutlined />}>Inbox</Tag>
                  )}
                </Space>
              </Descriptions.Item>
              {data.attachmentNames && (
                <Descriptions.Item label="Attachments">
                  <Space size={4} wrap>
                    <Tooltip title="Names only — the files themselves stay in the mailbox and are not stored here">
                      <PaperClipOutlined />
                    </Tooltip>
                    {data.attachmentNames.split(', ').map((name) => (
                      <Tag key={name}>{name}</Tag>
                    ))}
                  </Space>
                </Descriptions.Item>
              )}
            </Descriptions>

            <MessageBody html={data.bodyHtml} text={data.bodyText} />
          </Space>
        )}
      </Drawer>

      {m && (
        <LinkCompanyModal
          open={linkOpen}
          messageId={m.id}
          fromAddress={m.fromAddress}
          onClose={() => setLinkOpen(false)}
        />
      )}
    </>
  );
}

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
function MessageBody({ html, text }: { html?: string; text?: string }) {
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
          height: '60vh',
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
