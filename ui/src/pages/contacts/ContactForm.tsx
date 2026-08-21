import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Select, Space, Tooltip, Typography } from 'antd';
import { UserAddOutlined } from '@ant-design/icons';
import { useContactMutations } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import PersonSelect from '../../components/PersonSelect';
import PersonForm from '../people/PersonForm';
import type { ContactRequest, ContactResponse } from '../../api/types';

interface Props {
  open: boolean;
  editing?: ContactResponse | null;
  /**
   * Prefilled fields for a new contact — e.g. the company/person when adding from a
   * company drawer. Ignored when editing. Keep the object referentially stable.
   */
  defaults?: Partial<ContactRequest>;
  onClose: () => void;
}

/** How an address with no greeting is opened — MailTemplateService.NEUTRAL_SALUTATION. */
const NEUTRAL_SALUTATION = 'Good day';

export default function ContactForm({ open, editing, defaults, onClose }: Props) {
  const [form] = Form.useForm<ContactRequest>();
  const { create, update } = useContactMutations();
  // Mirrors of the two select values, kept because things outside the fields react to them:
  // the person list is scoped to the company, and the greeting hint changes once the address
  // belongs to a company and to nobody. Form.Item injects its own value/onChange over the
  // child's, so these are fed by the onChange it calls after its own — never by a `value`
  // prop, which it would override.
  const [companyId, setCompanyId] = useState<number>();
  const [personId, setPersonId] = useState<number>();
  const [personFormOpen, setPersonFormOpen] = useState(false);

  // A person created from here belongs to whichever company this contact is on.
  const personDefaults = useMemo(() => ({ companyId }), [companyId]);

  useEffect(() => {
    if (open) {
      form.resetFields();
      if (editing) {
        form.setFieldsValue({
          personId: editing.personId,
          companyId: editing.companyId,
          contactKind: editing.contactKind,
          contactValue: editing.contactValue,
          // The stored override, never the effective greeting: showing the person's here
          // would save a frozen copy of it onto the contact on the next save.
          greetingName: editing.ownGreetingName,
          notes: editing.notes,
        });
        setCompanyId(editing.companyId);
        setPersonId(editing.personId);
      } else {
        form.setFieldsValue(defaults ?? {});
        // Keeps the person dropdown scoped to the prefilled company.
        setCompanyId(defaults?.companyId);
        setPersonId(defaults?.personId);
      }
    }
  }, [open, editing, defaults, form]);

  const submit = (values: ContactRequest) => {
    const done = { onSuccess: onClose };
    const body = { ...values, greetingName: values.greetingName?.trim() || undefined };
    if (editing) update.mutate({ id: editing.id, body }, done);
    else create.mutate(body, done);
  };

  // An address on a company and nobody in particular: a chartering@ or ops@ desk. Worth
  // saying out loud, because the only way to ask for it is by leaving a box empty, and
  // an empty box otherwise reads as "not filled in yet" rather than as a choice.
  const companyWide = personId == null && companyId != null;
  const orphan = personId == null && companyId == null;

  return (
    <Modal
      open={open}
      title={editing ? 'Edit contact' : 'New contact'}
      okText="Save"
      confirmLoading={create.isPending || update.isPending}
      onCancel={onClose}
      onOk={() => form.submit()}
      destroyOnClose
    >
      <Form form={form} layout="vertical" onFinish={submit}>
        <Form.Item name="contactKind" label="Kind" rules={[{ required: true, message: 'kind is required' }]}>
          <Select options={[{ value: 'email', label: 'email' }, { value: 'phone', label: 'phone' }]} />
        </Form.Item>
        <Form.Item name="contactValue" label="Value" rules={[{ required: true, message: 'value is required' }]}>
          <Input placeholder="email address or phone number" />
        </Form.Item>
        <Form.Item name="companyId" label="Company">
          <CompanySelect allowClear onChange={setCompanyId} />
        </Form.Item>
        <Form.Item
          label="Person"
          tooltip="Who this email/phone belongs to. Leave it empty for an address that belongs to the company itself — a chartering@ or ops@ desk."
          extra={
            companyWide
              ? 'Empty — this belongs to the company itself, not to anyone in particular.'
              : undefined
          }
        >
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item name="personId" noStyle>
              <PersonSelect allowClear companyId={companyId} onChange={setPersonId} />
            </Form.Item>
            <Tooltip title="Create a new person">
              <Button icon={<UserAddOutlined />} onClick={() => setPersonFormOpen(true)} />
            </Tooltip>
          </Space.Compact>
        </Form.Item>

        {orphan && (
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="Pick a company, a person, or both"
            description="A contact filed under neither is listed on no screen — not the company drawer, not the People tab. Leave only the person empty for an address that belongs to the company itself."
          />
        )}

        <Form.Item
          name="greetingName"
          label="Greeting"
          tooltip={`The name a circular to this address opens with. Leave blank to use the person's greeting, or the general "${NEUTRAL_SALUTATION}," when there is no person.`}
          extra={
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {companyWide
                ? `Blank opens with "${NEUTRAL_SALUTATION}," — no number, no gender, no assumed role, so it reads the same to an owner, a charterer, a broker or an agent, and to one person or a whole desk. Fill this in only if you want a name after "Dear".`
                : "Blank uses the person's own greeting. Fill it in only to greet this particular address differently."}
            </Typography.Text>
          }
        >
          <Input
            allowClear
            maxLength={120}
            placeholder={
              companyWide ? `${NEUTRAL_SALUTATION}, (no name)` : editing?.greetingName ?? "the person's greeting"
            }
          />
        </Form.Item>

        <Form.Item name="notes" label="Notes">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>

      {/* Nested so a contact can be filed under someone who isn't in the system yet;
          the new person is selected straight away. */}
      <PersonForm
        open={personFormOpen}
        defaults={personDefaults}
        onCreated={(p) => {
          form.setFieldValue('personId', p.id);
          setPersonId(p.id);
        }}
        onClose={() => setPersonFormOpen(false)}
      />
    </Modal>
  );
}
