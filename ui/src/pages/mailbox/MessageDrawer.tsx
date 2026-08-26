import { useMemo, useState } from 'react';
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
  CompassOutlined,
  DisconnectOutlined,
  FolderOutlined,
  InboxOutlined,
  LinkOutlined,
  MailOutlined,
  PaperClipOutlined,
  PlusOutlined,
  SendOutlined,
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { useMailFolders, useMailMessage, useMailMessageMutations } from '../../mailbox/store';
import CargoForm from '../cargoes/CargoForm';
import PositionForm from '../openFleet/PositionForm';
import LinkCompanyModal from './LinkCompanyModal';
import MailReference from './MailReference';
import MessageBody from './MessageBody';
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
  // Which of the two records is being written out of this message, if either.
  const [recording, setRecording] = useState<'position' | 'cargo' | null>(null);
  // The menu's open state is held here rather than left to antd, because picking from it
  // opens a modal over this drawer: on a phone the menu survived that and stayed floating
  // over the form that had just opened underneath it.
  const [recordMenuOpen, setRecordMenuOpen] = useState(false);

  const m = data?.message;

  /*
   * What the message itself already answers, filled in before the form opens.
   *
   * `sourceMailMessageId` is the load-bearing one: the API links the record to the message
   * and marks it as having come from mail, which is what the Cargoes tab's "from mail"
   * filter reads. The rest is provenance the mail states outright — a reading is as old as
   * the email carrying it, not as old as the evening it got typed up, and the desk that
   * sent the list is the desk that reported it.
   *
   * A cargo gets no company prefilled, and that is the difference. "Who told us" is a fact
   * about a position; on a cargo the two company fields are charterer and broker, and which
   * of those the sender is depends on how the enquiry was routed. Guessing would fill in a
   * field nobody would then think to check.
   *
   * The timestamp is converted rather than passed on, because the two halves keep time
   * differently: `mail_messages.received_at` is a `LocalDateTime` and arrives with no zone
   * on it ("2026-08-26T14:40:11"), while a cargo's `received_at` and a position's
   * `reported_at` are `OffsetDateTime`, where an offset is not optional — handing the raw
   * string over is a 400 out of Jackson before the record is ever looked at. dayjs reads
   * the zone-less string in the browser's own zone, which is the same reading every screen
   * in the mailbox already gives it, so what gets stored is the instant shown on screen.
   */
  const mailReceivedAt = m ? dayjs(m.receivedAt).toISOString() : undefined;
  const positionDefaults = useMemo(
    () =>
      m
        ? {
            sourceMailMessageId: m.id,
            reportedByCompanyId: m.companyId,
            reportedAt: mailReceivedAt,
          }
        : undefined,
    [m?.id, m?.companyId, mailReceivedAt], // eslint-disable-line react-hooks/exhaustive-deps
  );
  const cargoDefaults = useMemo(
    () => (m ? { sourceMailMessageId: m.id, receivedAt: mailReceivedAt } : undefined),
    [m?.id, mailReceivedAt], // eslint-disable-line react-hooks/exhaustive-deps
  );

  const recordMenu = {
    items: [
      { key: 'position', icon: <CompassOutlined />, label: 'Vessel position' },
      { key: 'cargo', icon: <InboxOutlined />, label: 'Cargo' },
    ],
    onClick: ({ key }: { key: string }) => {
      setRecording(key as 'position' | 'cargo');
      setRecordMenuOpen(false);
    },
  };
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
              {/* Second, and the other half of what this desk does with its mail: a list of
                  open tonnage or an enquiry is read once and then wants typing up. Both
                  forms open over this drawer with the message still on screen beside them,
                  because copying figures out of an email you cannot see is how a laycan
                  ends up a month out. */}
              <Dropdown menu={recordMenu} open={recordMenuOpen} onOpenChange={setRecordMenuOpen}>
                <Button size="small" icon={<PlusOutlined />}>
                  Record…
                </Button>
              </Dropdown>
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

      {/* Both forms are mounted only once there is a message to reference — they are given
          the detail this drawer already holds, so the pane beside the fields is the same
          text being read behind them, not a second fetch of it. */}
      {data && (
        <>
          <PositionForm
            open={recording === 'position'}
            defaults={positionDefaults}
            reference={<MailReference detail={data} />}
            onClose={() => setRecording(null)}
          />
          <CargoForm
            open={recording === 'cargo'}
            defaults={cargoDefaults}
            reference={<MailReference detail={data} />}
            onClose={() => setRecording(null)}
          />
        </>
      )}
    </>
  );
}
