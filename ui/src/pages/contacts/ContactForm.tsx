import { useEffect, useMemo, useState } from 'react';
import { Button, Form, Input, Modal, Select, Space, Tooltip } from 'antd';
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

export default function ContactForm({ open, editing, defaults, onClose }: Props) {
  const [form] = Form.useForm<ContactRequest>();
  const { create, update } = useContactMutations();
  const [companyId, setCompanyId] = useState<number>();
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
          notes: editing.notes,
        });
        setCompanyId(editing.companyId);
      } else {
        form.setFieldsValue(defaults ?? {});
        // Keeps the person dropdown scoped to the prefilled company.
        setCompanyId(defaults?.companyId);
      }
    }
  }, [open, editing, defaults, form]);

  const submit = (values: ContactRequest) => {
    const done = { onSuccess: onClose };
    if (editing) update.mutate({ id: editing.id, body: values }, done);
    else create.mutate(values, done);
  };

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
          <CompanySelect allowClear value={companyId} onChange={(v) => { setCompanyId(v); form.setFieldValue('companyId', v); }} />
        </Form.Item>
        <Form.Item label="Person" tooltip="Who this email/phone belongs to — pick an existing person or create one">
          <Space.Compact style={{ width: '100%' }}>
            <Form.Item name="personId" noStyle>
              <PersonSelect allowClear companyId={companyId} />
            </Form.Item>
            <Tooltip title="Create a new person">
              <Button icon={<UserAddOutlined />} onClick={() => setPersonFormOpen(true)} />
            </Tooltip>
          </Space.Compact>
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
        onCreated={(p) => form.setFieldValue('personId', p.id)}
        onClose={() => setPersonFormOpen(false)}
      />
    </Modal>
  );
}
