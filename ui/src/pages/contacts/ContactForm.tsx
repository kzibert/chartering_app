import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Form, Input, Modal, Select, Space, Tooltip, Typography } from 'antd';
import { EditOutlined, UserAddOutlined } from '@ant-design/icons';
import { useContactMutations, usePerson } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import PersonSelect from '../../components/PersonSelect';
import PersonForm from '../people/PersonForm';
import type { ContactRequest, ContactResponse, PersonResponse } from '../../api/types';

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

/**
 * The labels offered for a phone. Suggestions rather than a closed set: the column is free
 * text so that an import can keep whatever word its source used, and a dropdown that
 * refused to show "Switchboard" because this list does not have it would throw away the
 * only thing the file had to say about the number.
 */
const PHONE_LABELS = ['Work', 'Mobile', 'Direct', 'Home', 'Fax', 'Other'];

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
  // The label field only exists for phones, so the form has to react to the kind rather
  // than just record it. Same mirroring reason as the two above.
  const [kind, setKind] = useState<string>();
  const [personFormOpen, setPersonFormOpen] = useState(false);
  // null = the nested form is creating a new person; a person = it is editing that one.
  const [personBeingEdited, setPersonBeingEdited] = useState<PersonResponse | null>(null);

  // The selected person, for the read-only job title below. Job titles live on the person,
  // so this form can show one but never save one — see the Form.Item's `extra`.
  const { data: person } = usePerson(personId);

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
          label: editing.label,
          // The stored override, never the effective greeting: showing the person's here
          // would save a frozen copy of it onto the contact on the next save.
          greetingName: editing.ownGreetingName,
          notes: editing.notes,
        });
        setCompanyId(editing.companyId);
        setPersonId(editing.personId);
        setKind(editing.contactKind);
      } else {
        form.setFieldsValue(defaults ?? {});
        // Keeps the person dropdown scoped to the prefilled company.
        setCompanyId(defaults?.companyId);
        setPersonId(defaults?.personId);
        setKind(defaults?.contactKind);
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
          <Select
            options={[{ value: 'email', label: 'email' }, { value: 'phone', label: 'phone' }]}
            onChange={setKind}
          />
        </Form.Item>
        <Form.Item name="contactValue" label="Value" rules={[{ required: true, message: 'value is required' }]}>
          <Input placeholder="email address or phone number" />
        </Form.Item>

        {/* Phones only. "Work email" is a guess about the person rather than a fact about
            the address, so an email is offered no label to carry — and the server drops one
            anyway if the kind is changed out from under it. */}
        {kind === 'phone' && (
          <Form.Item
            name="label"
            label="Kind of line"
            tooltip="What sort of number this is. A free-text list rather than a fixed one, so a label off an imported file is kept as it was written."
          >
            <Select
              allowClear
              placeholder="Not recorded"
              options={PHONE_LABELS.map((l) => ({ value: l, label: l }))}
            />
          </Form.Item>
        )}
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
              <Button
                icon={<UserAddOutlined />}
                onClick={() => {
                  setPersonBeingEdited(null);
                  setPersonFormOpen(true);
                }}
              />
            </Tooltip>
          </Space.Compact>
        </Form.Item>

        {/* Shown, not edited. The position is a fact about the person — their mobile and
            their two mailboxes all carry the same one — so it is stored once on the person
            and read here. Editing opens the person, which is where it can be changed for
            all of their addresses at once. */}
        {personId != null && (
          <Form.Item
            label="Job title"
            tooltip="The position this person holds at the company. Stored on the person, so it shows on every address and number of theirs."
            extra={
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                Belongs to the person, not to this address — editing it changes it on all of
                their emails and numbers.
              </Typography.Text>
            }
          >
            <Space size={4} wrap>
              {person?.jobTitle ? (
                <Typography.Text>{person.jobTitle}</Typography.Text>
              ) : (
                <Typography.Text type="secondary">Not set</Typography.Text>
              )}
              {person && (
                <Button
                  type="link"
                  size="small"
                  icon={<EditOutlined />}
                  onClick={() => {
                    setPersonBeingEdited(person);
                    setPersonFormOpen(true);
                  }}
                >
                  {person.jobTitle ? 'Edit' : 'Add one'}
                </Button>
              )}
            </Space>
          </Form.Item>
        )}

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
        editing={personBeingEdited}
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
