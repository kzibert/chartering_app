import { useState } from 'react';
import {
  App,
  Button,
  Descriptions,
  Drawer,
  Dropdown,
  Popconfirm,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  BankOutlined,
  CloudServerOutlined,
  DisconnectOutlined,
  FolderOutlined,
  InboxOutlined,
  LinkOutlined,
  MailOutlined,
  PaperClipOutlined,
  SendOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useMailFolders, useMailMessage, useMailMessageMutations } from '../../mailbox/store';
import LinkCompanyModal from './LinkCompanyModal';
import ReplyModal from './ReplyModal';

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
  const [replyOpen, setReplyOpen] = useState(false);

  const m = data?.message;
  const named = (folders.data ?? []).filter((f) => f.id != null);

  const moveMenu = {
    items: [
      { key: 'inbox', icon: <InboxOutlined />, label: 'Take out of the folder' },
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
                ? 'Taken out of its folder'
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
        // The header is one flex row of subject and buttons, and the buttons are the fixed
        // half of it: the subject has to be the part that gives way. Clamping it at a fixed
        // width does not do that — it still claims that width whatever the buttons need
        // beside it, and runs straight over them — so it takes whatever they leave and
        // wraps onto a second line, which for the mail this desk reads is usually the whole
        // subject. Past two lines it is cut, and the tooltip carries the rest.
        //
        // `width: 0` is what makes it give way. Drawer wraps this in an element of its own
        // that antd leaves at min-width:auto, so the header can otherwise only shrink to
        // the widest word inside it — and these subjects carry 60-character report ids.
        // Declaring the text zero-wide and letting flex-grow size it keeps the subject out
        // of that measurement entirely.
        title={
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, minWidth: 0 }}>
            <MailOutlined style={{ marginTop: 5 }} />
            {/* The box that gives way is this one, not the text: a clamped paragraph is
                laid out as a -webkit-box, and a flex item is blockified — which throws the
                clamp away and leaves the second line half empty. Paragraph rather than
                Text, because only the block one clamps by line count at all. */}
            <div style={{ flex: 1, width: 0 }}>
              <Typography.Paragraph
                strong
                ellipsis={{ rows: 2, tooltip: m?.subject || undefined }}
                style={{ margin: 0, overflowWrap: 'anywhere' }}
              >
                {m?.subject || '(no subject)'}
              </Typography.Paragraph>
            </div>
          </div>
        }
        extra={
          m && (
            <Space wrap>
              {/* First, and the only primary button on the drawer: reading a message is
                  what this screen is for, and answering it is what reading one leads to. */}
              <Button
                size="small"
                type="primary"
                icon={<SendOutlined />}
                onClick={() => setReplyOpen(true)}
              >
                Reply
              </Button>
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
                  {/* A registered shipowner's name runs to sixty characters, and a Button
                      will not cut its own label — on a phone this one button was wider
                      than the screen and carried the rest of the row off with it. The cap
                      is on the button and the clip on the span inside it, because the
                      button is a flex box and only its child can be the part that gives
                      way. The full name is on the drawer's From row either way. */}
                  <Button
                    size="small"
                    icon={<BankOutlined />}
                    style={{ maxWidth: 200 }}
                    onClick={() => onOpenCompany(m.companyId!)}
                  >
                    <span
                      style={{
                        minWidth: 0,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {m.companyName}
                    </span>
                  </Button>
                  {/* No Tooltip around the trigger: nesting one inside Popconfirm makes the
                      two popups fight over it. The confirm text names the company instead,
                      which is what the bare icon was leaning on the tooltip to say. */}
                  <Popconfirm
                    title={`Detach this message from ${m.companyName}?`}
                    description="The sender goes back to the automatic matcher, so a later re-link may attach it again. Nothing is deleted."
                    okText="Detach"
                    onConfirm={() =>
                      unlink.mutate(m.id, { onSuccess: () => toast.success('Link removed') })
                    }
                  >
                    <Button size="small" icon={<DisconnectOutlined />} />
                  </Popconfirm>
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
                  {/* Only ever says what this app did. A reply sent from Outlook leaves no
                      trace here — it is in the mailbox's Sent folder, not in this record. */}
                  {data.repliedAt && (
                    <Tooltip title={`Answered from this app on ${dayjs(data.repliedAt).format('YYYY-MM-DD HH:mm')}. A reply sent from another mail client would not show here.`}>
                      <Tag color="green" icon={<SendOutlined />}>replied</Tag>
                    </Tooltip>
                  )}
                  {/* Where the mail server keeps it — for mail the mailbox's own filters
                      sorted on arrival, this is the only answer to "why did I not see it in
                      the Inbox?". The app's own filing follows it, when there is any. */}
                  {m.imapFolder && (
                    <Tooltip title="The folder this is in on the mail server">
                      <Tag icon={<CloudServerOutlined />}>{m.imapFolder}</Tag>
                    </Tooltip>
                  )}
                  {m.folderName ? (
                    <Tag color={m.filedByRuleId ? 'geekblue' : 'default'} icon={<FolderOutlined />}>
                      {m.folderName}
                      {m.filedByRuleId ? ' (by rule)' : ''}
                    </Tag>
                  ) : (
                    <Tooltip title="No rule of this app has filed it">
                      <Tag icon={<InboxOutlined />}>Unfiled</Tag>
                    </Tooltip>
                  )}
                </Space>
              </Descriptions.Item>
              {data.attachmentNames && (
                <Descriptions.Item label="Attachments">
                  <Space size={4} wrap>
                    <Tooltip title="Names only — the files themselves stay in the mailbox and are not stored here">
                      <PaperClipOutlined />
                    </Tooltip>
                    {/* Report attachments arrive named as one 120-character word. A Tag
                        does not wrap on its own, so without the break it carries the whole
                        drawer out past its own edge. */}
                    {data.attachmentNames.split(', ').map((name) => (
                      <Tag key={name} style={{ whiteSpace: 'normal', wordBreak: 'break-all' }}>
                        {name}
                      </Tag>
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

      {/* Fed the message the drawer has already loaded rather than the id: the composer
          quotes nothing itself, but it needs the sender, the subject and the links, and
          fetching them a second time would be a second chance to disagree. */}
      <ReplyModal open={replyOpen} detail={data} onClose={() => setReplyOpen(false)} />
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
