import { App, Button, Popover, Space, Tooltip, Typography } from 'antd';
import { ExportOutlined, WhatsAppOutlined } from '@ant-design/icons';
import { useContactMutations, useWhatsappSettings } from '../api/hooks';
import { renderWhatsappMessage, toWhatsappNumber, whatsappLink } from './whatsapp';
import type { ContactResponse } from '../api/types';

/** WhatsApp green, so the confirmed link is recognisable at a glance in a row of tags. */
const WA_GREEN = '#25d366';

/**
 * The chat link on a number already known to be on WhatsApp.
 *
 * Not gated behind the Edit toggle, unlike the check below: messaging a broker is reading
 * the contact, not editing it, and the whole point of having flagged the number is to be
 * able to reach them from wherever the contact happens to be listed.
 */
export function WhatsappChatLink({ ct }: { ct: ContactResponse }) {
  const { data: settings } = useWhatsappSettings();

  if (ct.contactKind !== 'phone' || !ct.hasWhatsapp) return null;

  const { digits } = toWhatsappNumber(ct.contactValue);
  if (!digits) return null;

  const message = renderWhatsappMessage(settings?.message ?? '', ct);
  const who = ct.personName ?? ct.companyName ?? 'this contact';

  return (
    <Tooltip title={`Open the WhatsApp chat with ${who}${message ? ` — "${message}"` : ''}`}>
      <Button
        type="text"
        size="small"
        aria-label={`Open WhatsApp chat with ${who}`}
        href={whatsappLink(digits, message)}
        target="_blank"
        rel="noreferrer"
        icon={<WhatsAppOutlined style={{ color: WA_GREEN }} />}
      />
    </Tooltip>
  );
}

/**
 * Check whether a number is on WhatsApp, and record the answer.
 *
 * There is no API behind this and deliberately no pretence of one: nothing available to us
 * can ask WhatsApp whether a number is registered. So the popover opens a chat with the
 * configured greeting prefilled, the user looks at what WhatsApp shows, and comes back to
 * say yes or no. The flag is that answer — somebody's observation, which is why it is
 * offered only in edit mode and why nothing ever sets or clears it on its own.
 *
 * <p>The number is shown as it will be sent, because the stored values are hand-typed in
 * every format there is and the one that matters is the one wa.me actually receives.
 */
export function WhatsappCheckButton({ ct }: { ct: ContactResponse }) {
  const { data: settings } = useWhatsappSettings();
  const { setHasWhatsapp } = useContactMutations();
  const { message: toast } = App.useApp();

  if (ct.contactKind !== 'phone') return null;

  const { digits, warning } = toWhatsappNumber(ct.contactValue);
  const text = renderWhatsappMessage(settings?.message ?? '', ct);
  const who = ct.personName ?? ct.companyName ?? 'this contact';

  const set = (hasWhatsapp: boolean) =>
    setHasWhatsapp.mutate(
      { id: ct.id, hasWhatsapp },
      {
        onSuccess: () =>
          toast.success(
            hasWhatsapp
              ? `${ct.contactValue} is on WhatsApp`
              : `${ct.contactValue} is no longer flagged as on WhatsApp`,
          ),
      },
    );

  const content = (
    <Space direction="vertical" size={8} style={{ maxWidth: 320 }}>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        WhatsApp cannot be asked whether a number is registered, so open the chat and see
        for yourself — then say what happened.
      </Typography.Text>
      <Typography.Text>
        Opens as <Typography.Text code>+{digits || '—'}</Typography.Text>
      </Typography.Text>
      {warning && (
        <Typography.Text type="warning" style={{ fontSize: 12 }}>
          {warning}
        </Typography.Text>
      )}
      {text && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          Message: “{text}”
        </Typography.Text>
      )}
      <Button
        icon={<ExportOutlined />}
        disabled={!digits}
        href={digits ? whatsappLink(digits, text) : undefined}
        target="_blank"
        rel="noreferrer"
        block
      >
        Open WhatsApp
      </Button>
      {ct.hasWhatsapp ? (
        <Button danger block loading={setHasWhatsapp.isPending} onClick={() => set(false)}>
          Not on WhatsApp — clear the flag
        </Button>
      ) : (
        <Button
          type="primary"
          block
          disabled={!digits}
          loading={setHasWhatsapp.isPending}
          onClick={() => set(true)}
        >
          Yes — it's on WhatsApp
        </Button>
      )}
    </Space>
  );

  const title = ct.hasWhatsapp
    ? `On WhatsApp — open the chat with ${who}, or clear the flag`
    : `Check whether ${who} is on WhatsApp`;

  return (
    <Popover content={content} title="WhatsApp" trigger="click" placement="topRight">
      <Tooltip title={title}>
        <Button
          type="text"
          size="small"
          aria-label={title}
          // Left uncoloured even when flagged: the green icon next to the number is
          // already saying so, and two greens in one row read as two separate links.
          icon={<WhatsAppOutlined />}
        >
          {ct.hasWhatsapp ? 'WA' : 'WA?'}
        </Button>
      </Tooltip>
    </Popover>
  );
}
