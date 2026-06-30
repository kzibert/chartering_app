import { useEffect, useState } from 'react';
import { Form, Input, Modal, Select } from 'antd';
import { useContactMutations } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import PersonSelect from '../../components/PersonSelect';
import type { ContactRequest, ContactResponse } from '../../api/types';

interface Props {
  open: boolean;
  editing?: ContactResponse | null;
  onClose: () => void;
}

export default function ContactForm({ open, editing, onClose }: Props) {
  const [form] = Form.useForm<ContactRequest>();
  const { create, update } = useContactMutations();
  const [companyId, setCompanyId] = useState<number>();

  useEffect(() => {
    if (open) {
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
        form.resetFields();
        setCompanyId(undefined);
      }
    }
  }, [open, editing, form]);

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
        <Form.Item name="personId" label="Person">
          <PersonSelect allowClear companyId={companyId} />
        </Form.Item>
        <Form.Item name="notes" label="Notes">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
