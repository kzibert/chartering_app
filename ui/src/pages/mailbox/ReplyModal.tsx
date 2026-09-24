import { useEffect, useState } from 'react';
import { App, Checkbox, Descriptions, Input, Modal, Space, Typography } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { campaignsApi } from '../../api/campaigns';
import RichTextEditor from '../../components/RichTextEditor';
import { useMailMessageMutations } from '../../mailbox/store';
import { FooterPicker, FromLine, SendBlockedAlert, useSendRoute } from './SendRoute';
import type { MailMessageDetail } from '../../api/types';

interface Props {
  open: boolean;
  /** The message being answered, already loaded by the drawer — never fetched again here. */
  detail?: MailMessageDetail;
  onClose: () => void;
}

/** "Re: x" once, however many times a thread has been round. */
function replySubject(subject?: string): string {
  const s = (subject ?? '').trim();
  if (!s) return 'Re:';
  return /^re\s*:/i.test(s) ? s : `Re: ${s}`;
}

/**
 * Answering one message, from the app.
 *
 * <p>The composer is deliberately thin: the footer, the quoted original and the mail-merge
 * are all applied server-side, so what is stored as having been sent is the same string the
 * mail server was handed. This screen picks which footer and whether to quote — it never
 * builds the message. That also keeps the editor holding only what the user actually wrote,
 * so a 100KB Outlook chain is not something they have to scroll past to type.
 *
 * <p>Sending goes through the mailbox over SMTP whatever the Circulars tab is set to. There
 * is no provider choice here and there should not be one: a reply has to come from the
 * address the correspondent wrote to.
 */
export default function ReplyModal({ open, detail, onClose }: Props) {
  const { message: toast } = App.useApp();
  const { reply } = useMailMessageMutations();

  const [to, setTo] = useState('');
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [footerId, setFooterId] = useState<number | null>(null);
  const [includeOriginal, setIncludeOriginal] = useState(true);

  const route = useSendRoute();
  const placeholdersQ = useQuery({
    queryKey: ['campaign', 'placeholders'],
    queryFn: campaignsApi.placeholders,
  });

  const m = detail?.message;

  // Every open starts from the message, not from whatever the last reply left behind: the
  // drawer stays mounted between messages, and a half-written answer to somebody else is
  // the one thing that must never appear in this box.
  useEffect(() => {
    if (!open || !m) return;
    setTo(m.fromAddress);
    setSubject(replySubject(m.subject));
    setBody('');
    setIncludeOriginal(true);
    setFooterId(null);
  }, [open, m?.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const { blocked } = route;

  const send = () => {
    if (!m) return;
    reply.mutate(
      {
        id: m.id,
        body: { to: to.trim(), subject: subject.trim(), bodyHtml: body, footerId, includeOriginal },
      },
      {
        onSuccess: (sent) => {
          toast.success(`Reply sent to ${sent.toAddress}`);
          onClose();
        },
        // The error body carries the server's own words — the provider's refusal, or the
        // list of settings still missing — and they are more use than "sending failed".
        onError: (e: unknown) => {
          const detailMsg =
            (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
          toast.error(detailMsg || 'The reply could not be sent.');
        },
      },
    );
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      width={860}
      destroyOnClose
      title="Reply"
      okText="Send reply"
      okButtonProps={{
        icon: <SendOutlined />,
        disabled: blocked || !to.trim() || !subject.trim() || !body.trim(),
      }}
      confirmLoading={reply.isPending}
      onOk={send}
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <SendBlockedAlert route={route} what="reply" />

        <Descriptions size="small" column={1} bordered>
          <Descriptions.Item label="From">
            <FromLine route={route} />
          </Descriptions.Item>
          <Descriptions.Item label="To">
            {/* Editable: a broker who writes from a personal address often wants the
                answer at the desk one, and only the person reading the thread knows. */}
            <Input value={to} onChange={(e) => setTo(e.target.value)} maxLength={320} />
          </Descriptions.Item>
        </Descriptions>

        <Input
          size="large"
          value={subject}
          onChange={(e) => setSubject(e.target.value)}
          maxLength={300}
          placeholder="Subject"
        />

        <RichTextEditor
          value={body}
          onChange={setBody}
          placeholders={placeholdersQ.data}
          minHeight={220}
        />

        <Space wrap>
          <FooterPicker open={open} resetKey={`${open}-${m?.id}`} value={footerId} onChange={setFooterId} />
          <Checkbox
            checked={includeOriginal}
            onChange={(e) => setIncludeOriginal(e.target.checked)}
          >
            Quote the message below
          </Checkbox>
        </Space>

        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          The footer and the quoted message are added when it is sent, so they are not in
          the box above. Placeholders such as {'{{greeting}}'} are filled in from whoever
          this message is linked to.
        </Typography.Text>
      </Space>
    </Modal>
  );
}
