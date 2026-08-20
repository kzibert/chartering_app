import { useEffect, useState } from 'react';
import { App, Alert, Checkbox, Form, Modal, Typography } from 'antd';
import CompanySelect from '../../components/CompanySelect';
import PersonSelect from '../../components/PersonSelect';
import { useMailMessageMutations } from '../../mailbox/store';

interface Props {
  open: boolean;
  messageId: number;
  fromAddress: string;
  onClose: () => void;
}

/**
 * Attach a message to a company by hand, for a sender the contacts do not know.
 *
 * <p>The checkbox is the part that matters. Linking the message fixes the one row you are
 * looking at; recording the address as a contact means every later message from that sender
 * links itself, and that the address becomes visible to the rest of the app — the company
 * drawer, the circulation lists, the bulk collect. It is on by default because arriving here
 * at all means the address was worth knowing about.
 */
export default function LinkCompanyModal({ open, messageId, fromAddress, onClose }: Props) {
  const { message } = App.useApp();
  const { link } = useMailMessageMutations();
  const [companyId, setCompanyId] = useState<number>();
  const [personId, setPersonId] = useState<number>();
  const [createContact, setCreateContact] = useState(true);

  // Reset per opening: the previous message's company sitting in the form would be an easy
  // way to file mail under the wrong firm.
  useEffect(() => {
    if (open) {
      setCompanyId(undefined);
      setPersonId(undefined);
      setCreateContact(true);
    }
  }, [open, messageId]);

  const submit = () =>
    link.mutate(
      { id: messageId, body: { companyId, personId, createContact } },
      {
        onSuccess: (m) => {
          message.success(
            createContact
              ? `Linked to ${m.companyName} and saved ${fromAddress} as a contact`
              : `Linked to ${m.companyName}`,
          );
          onClose();
        },
      },
    );

  return (
    <Modal
      open={open}
      title="Link this message to a company"
      okText="Link"
      okButtonProps={{ disabled: companyId == null && personId == null, loading: link.isPending }}
      onOk={submit}
      onCancel={onClose}
      destroyOnClose
    >
      <Form layout="vertical">
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message={
            <>
              Sender: <Typography.Text code>{fromAddress}</Typography.Text>
            </>
          }
        />
        <Form.Item label="Company">
          <CompanySelect
            value={companyId}
            allowClear
            onChange={(v) => {
              setCompanyId(v);
              // The person list is scoped to the company, so a stale person from another
              // firm must not survive the change.
              setPersonId(undefined);
            }}
          />
        </Form.Item>
        <Form.Item
          label="Person"
          extra="Optional. Picking one without a company files the message under their employer."
        >
          <PersonSelect value={personId} companyId={companyId} allowClear onChange={setPersonId} />
        </Form.Item>
        <Form.Item>
          <Checkbox
            checked={createContact}
            onChange={(e) => setCreateContact(e.target.checked)}
          >
            Also save <Typography.Text code>{fromAddress}</Typography.Text> as a contact, so
            later mail from it links itself
          </Checkbox>
        </Form.Item>
      </Form>
    </Modal>
  );
}
