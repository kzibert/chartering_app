import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Checkbox,
  Descriptions,
  Empty,
  Input,
  List,
  Modal,
  Select,
  Space,
  Spin,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import { ArrowLeftOutlined, SendOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { campaignsApi } from '../../api/campaigns';
import { useCompanyContacts } from '../../api/hooks';
import { useMailMessageMutations } from '../../mailbox/store';
import RichTextEditor from '../../components/RichTextEditor';
import { FooterPicker, FromLine, SendBlockedAlert, useSendRoute } from '../mailbox/SendRoute';
import type { ContactResponse } from '../../api/types';

/**
 * Writing to a firm from its own record: pick who, then write.
 *
 * <p><b>The desk addresses come first.</b> A company-wide {@code chartering@} or {@code ops@}
 * is the address that is read whoever is in the office this week, and the one most messages to
 * a firm should at least copy; the people follow, grouped under their names.
 *
 * <p><b>Picking is one click, copying is a tick.</b> Clicking a row writes to that address
 * alone; the box beside each row adds it to the message instead. The first address picked is
 * who it is to and the rest go on copy, which is how somebody writing to a firm puts the person
 * first and the desk beside them — and any copied row can be made the To.
 *
 * <p>Then the Mailbox's reply composer, less the quote: the same route out, the same From, the
 * same footer library starting on the reply default, and the same row in the day's count. An
 * address that has bounced or been banned is shown, so the list is the whole record, but cannot
 * be picked — nobody writes to a dead address on purpose.
 */
export default function ReachOutModal({
  open,
  companyId,
  companyName,
  onClose,
}: {
  open: boolean;
  companyId: number;
  companyName: string;
  onClose: () => void;
}) {
  const { message: toast } = App.useApp();
  const { data: contacts, isLoading } = useCompanyContacts(open ? companyId : undefined);
  const { compose } = useMailMessageMutations();
  const route = useSendRoute();
  const placeholdersQ = useQuery({ queryKey: ['campaign', 'placeholders'], queryFn: campaignsApi.placeholders });

  const [step, setStep] = useState<'pick' | 'write'>('pick');
  /** Contact ids in the order they were picked: the first is To, the rest are copied. */
  const [picked, setPicked] = useState<number[]>([]);
  const [to, setTo] = useState('');
  const [cc, setCc] = useState<string[]>([]);
  const [subject, setSubject] = useState('');
  const [body, setBody] = useState('');
  const [footerId, setFooterId] = useState<number | null>(null);

  // Every opening starts clean: the drawer is reused for the next firm, and half a message to
  // somebody else is the one thing that must never be waiting in this box.
  useEffect(() => {
    if (!open) return;
    setStep('pick');
    setPicked([]);
    setSubject('');
    setBody('');
    setFooterId(null);
  }, [open, companyId]);

  const emails = useMemo(() => {
    const list = (contacts ?? []).filter((c) => c.contactKind === 'email');
    // Desk addresses first, then people by name; the main address heads its group.
    return [...list].sort((a, b) => {
      if (a.companyWide !== b.companyWide) return a.companyWide ? -1 : 1;
      const byPerson = (a.personName ?? '').localeCompare(b.personName ?? '');
      if (byPerson !== 0) return byPerson;
      if (a.main !== b.main) return a.main ? -1 : 1;
      return a.contactValue.localeCompare(b.contactValue);
    });
  }, [contacts]);
  const byId = useMemo(() => new Map(emails.map((c) => [c.id, c])), [emails]);

  const unusable = (c: ContactResponse) =>
    c.banned ? 'Banned — not written to from here' : !c.working ? 'Not working — mail to it has bounced' : null;

  const toggle = (id: number) =>
    setPicked((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));

  const write = (ids: number[]) => {
    const addresses = ids.map((id) => byId.get(id)!.contactValue);
    setPicked(ids);
    setTo(addresses[0] ?? '');
    setCc(addresses.slice(1));
    setStep('write');
  };

  // The merge runs against the contact only while the To is still the address picked for it:
  // typed over, it is somebody else, and a greeting for the wrong person is worse than none.
  const toContact = picked.length > 0 ? byId.get(picked[0]) : undefined;
  const contactId =
    toContact && toContact.contactValue.trim().toLowerCase() === to.trim().toLowerCase()
      ? toContact.id
      : undefined;

  const send = () =>
    compose.mutate(
      {
        to: to.trim(),
        cc: cc.map((a) => a.trim()).filter(Boolean),
        subject: subject.trim(),
        bodyHtml: body,
        footerId,
        contactId,
      },
      {
        onSuccess: (sent) => {
          toast.success(`Sent to ${sent.toAddress}${cc.length ? ` and ${cc.length} on copy` : ''}`);
          onClose();
        },
        onError: (e: unknown) => {
          const detail = (e as { response?: { data?: { message?: string } } })?.response?.data?.message;
          toast.error(detail || 'The message could not be sent.');
        },
      },
    );

  const who = (c: ContactResponse) =>
    c.companyWide ? (
      <Tag color="blue">company-wide</Tag>
    ) : (
      <Typography.Text type="secondary">
        {c.personName}
        {c.jobTitle ? ` · ${c.jobTitle}` : ''}
      </Typography.Text>
    );

  const pickList = (
    <>
      {isLoading ? (
        <Spin />
      ) : emails.length === 0 ? (
        <Empty description={`No email addresses on file for ${companyName}.`} />
      ) : (
        <List
          size="small"
          bordered
          dataSource={emails}
          renderItem={(c) => {
            const why = unusable(c);
            const at = picked.indexOf(c.id);
            return (
              <List.Item
                style={{ cursor: why ? 'not-allowed' : 'pointer', opacity: why ? 0.5 : 1 }}
                onClick={() => !why && write([c.id])}
                extra={
                  at === 0 ? <Tag color="green">To</Tag> : at > 0 ? <Tag>CC</Tag> : null
                }
              >
                <Space size={8} wrap style={{ minWidth: 0 }}>
                  <Tooltip title={why ?? 'Tick to add it to the message; the first ticked is To, the rest are copied'}>
                    <Checkbox
                      checked={at >= 0}
                      disabled={!!why}
                      onClick={(e) => e.stopPropagation()}
                      onChange={() => toggle(c.id)}
                    />
                  </Tooltip>
                  <Typography.Text strong>{c.contactValue}</Typography.Text>
                  {who(c)}
                  {c.main && <Tag color="gold">main</Tag>}
                  {c.personLeft && <Tag color="orange">left the company</Tag>}
                  {c.noCirc && (
                    <Tooltip title="Kept out of circulars, and still the right address to write to by hand">
                      <Tag>no circulars</Tag>
                    </Tooltip>
                  )}
                  {why && <Tag color="red">{c.banned ? 'banned' : 'not working'}</Tag>}
                </Space>
              </List.Item>
            );
          }}
        />
      )}
      <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block', marginTop: 8 }}>
        Click an address to write to it alone, or tick several: the first ticked is To and the
        rest go on copy.
      </Typography.Text>
    </>
  );

  const composer = (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <SendBlockedAlert route={route} what="message" />
      <Descriptions size="small" column={1} bordered>
        <Descriptions.Item label="From">
          <FromLine route={route} />
        </Descriptions.Item>
        <Descriptions.Item label="To">
          <Input value={to} onChange={(e) => setTo(e.target.value)} maxLength={320} />
        </Descriptions.Item>
        <Descriptions.Item label="CC">
          <Select
            mode="tags"
            style={{ width: '100%' }}
            value={cc}
            onChange={setCc}
            tokenSeparators={[',', ';', ' ']}
            placeholder="Nobody on copy"
            options={emails
              .filter((c) => !unusable(c) && c.contactValue !== to)
              .map((c) => ({ value: c.contactValue, label: c.contactValue }))}
          />
          {cc.length > 0 && (
            <Typography.Link
              style={{ fontSize: 12 }}
              onClick={() => {
                // Swap the first copy into To — the "make this one the To" of the pick list,
                // without going back to it.
                const [first, ...rest] = cc;
                setCc(to.trim() ? [to.trim(), ...rest] : rest);
                setTo(first);
              }}
            >
              Swap To with the first copy
            </Typography.Link>
          )}
        </Descriptions.Item>
      </Descriptions>

      <Input
        size="large"
        value={subject}
        onChange={(e) => setSubject(e.target.value)}
        maxLength={300}
        placeholder="Subject"
      />

      <RichTextEditor value={body} onChange={setBody} placeholders={placeholdersQ.data} minHeight={220} />

      <FooterPicker open={open} resetKey={`${open}-${companyId}`} value={footerId} onChange={setFooterId} />

      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        The footer is added when it is sent, so it is not in the box above. Placeholders such as{' '}
        {'{{greeting}}'} are filled in from whoever the To address belongs to.
      </Typography.Text>
    </Space>
  );

  return (
    <Modal
      open={open}
      onCancel={onClose}
      width={860}
      destroyOnClose
      title={step === 'pick' ? `Reach out to ${companyName}` : `Write to ${companyName}`}
      footer={
        step === 'pick' ? (
          <Space>
            <Button onClick={onClose}>Cancel</Button>
            <Button type="primary" disabled={picked.length === 0} onClick={() => write(picked)}>
              {picked.length === 0
                ? 'Write'
                : `Write to ${picked.length} ${picked.length === 1 ? 'address' : 'addresses'}`}
            </Button>
          </Space>
        ) : (
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => setStep('pick')}>
              Addresses
            </Button>
            <Button
              type="primary"
              icon={<SendOutlined />}
              loading={compose.isPending}
              disabled={route.blocked || !to.trim() || !subject.trim() || !body.trim()}
              onClick={send}
            >
              Send
            </Button>
          </Space>
        )
      }
    >
      {step === 'pick' ? pickList : composer}
    </Modal>
  );
}
