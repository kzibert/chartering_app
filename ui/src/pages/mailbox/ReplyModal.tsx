import { useState } from 'react';
import { App, Button, Checkbox, Descriptions, Input, Space, Typography } from 'antd';
import { EyeOutlined, SendOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { campaignsApi } from '../../api/campaigns';
import RichTextEditor from '../../components/RichTextEditor';
import { useMailMessageMutations } from '../../mailbox/store';
import ComposerWindow from '../../compose/ComposerWindow';
import PreviewModal from '../../compose/PreviewModal';
import { mailboxApi } from '../../api/mailbox';
import type { MailPreviewResponse, MailReplyRequest } from '../../api/types';
import type { ReplyDraft } from '../../compose/store';
import { FooterPicker, FromLine, SendBlockedAlert, useSendRoute } from './SendRoute';

interface Props {
  draft: ReplyDraft;
  onChange: (patch: Partial<ReplyDraft>) => void;
  onMinimize: () => void;
  /** Sent or thrown away: either way the draft is done with. */
  onDone: () => void;
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
 *
 * <p>Everything typed lives in the draft (see {@code compose/store}), not here, so the window
 * can be minimised while the rest of the app is used and come back exactly as it was left.
 */
export default function ReplyModal({ draft, onChange, onMinimize, onDone }: Props) {
  const { message: toast } = App.useApp();
  const { reply } = useMailMessageMutations();
  const route = useSendRoute();
  const placeholdersQ = useQuery({
    queryKey: ['campaign', 'placeholders'],
    queryFn: campaignsApi.placeholders,
  });

  const { to, subject, body, footerId, includeOriginal } = draft;
  const [preview, setPreview] = useState<(() => Promise<MailPreviewResponse>) | null>(null);

  const request = (): MailReplyRequest => ({
    to: to.trim(),
    subject: subject.trim(),
    bodyHtml: body,
    footerId: footerId ?? null,
    includeOriginal,
  });
  const ready = !!to.trim() && !!subject.trim() && !!body.trim();

  const send = () =>
    reply.mutate(
      { id: draft.messageId, body: request() },
      {
        onSuccess: (sent) => {
          toast.success(`Reply sent to ${sent.toAddress}`);
          onDone();
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

  return (
    <ComposerWindow
      title="Reply"
      dirty={body.trim().length > 0}
      onMinimize={onMinimize}
      onDiscard={onDone}
      footer={
        <Space>
          <Button
            icon={<EyeOutlined />}
            disabled={!ready}
            onClick={() => {
              const body = request();
              setPreview(() => () => mailboxApi.previewReply(draft.messageId, body));
            }}
          >
            Preview
          </Button>
          <Button
            type="primary"
            icon={<SendOutlined />}
            loading={reply.isPending}
            disabled={route.blocked || !ready}
            onClick={send}
          >
            Send reply
          </Button>
        </Space>
      }
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
            <Input value={to} onChange={(e) => onChange({ to: e.target.value })} maxLength={320} />
          </Descriptions.Item>
        </Descriptions>

        <Input
          size="large"
          value={subject}
          onChange={(e) => onChange({ subject: e.target.value, label: e.target.value || 'Reply' })}
          maxLength={300}
          placeholder="Subject"
        />

        <RichTextEditor
          value={body}
          onChange={(v) => onChange({ body: v })}
          placeholders={placeholdersQ.data}
          minHeight={220}
        />

        <Space wrap>
          <FooterPicker value={footerId} onChange={(id) => onChange({ footerId: id })} />
          <Checkbox
            checked={includeOriginal}
            onChange={(e) => onChange({ includeOriginal: e.target.checked })}
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
      <PreviewModal load={preview} onClose={() => setPreview(null)} />
    </ComposerWindow>
  );
}
