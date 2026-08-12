import { useEffect, useRef, useState } from 'react';
import { AutoComplete, Form, Input, Modal, Typography } from 'antd';
import { usePersonMutations } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import { resolveGreeting } from './resolveGreeting';
import type { PersonRequest, PersonResponse } from '../../api/types';

const TITLE_OPTIONS = ['Mr.', 'Mrs.', 'Ms.', 'Miss', 'Capt.', 'Dr.', 'Eng.', 'Prof.', 'Sir', 'Madam'].map(
  (t) => ({ value: t }),
);

interface Props {
  open: boolean;
  editing?: PersonResponse | null;
  /**
   * Prefilled fields for a new person — e.g. the company when adding from a company
   * drawer. Ignored when editing. Keep the object referentially stable (useMemo).
   */
  defaults?: Partial<PersonRequest>;
  /** Called with the saved person after a *create*, so callers can select it. */
  onCreated?: (person: PersonResponse) => void;
  onClose: () => void;
}

export default function PersonForm({ open, editing, defaults, onCreated, onClose }: Props) {
  const [form] = Form.useForm<PersonRequest>();
  const { create, update } = usePersonMutations();

  // The greeting is filled in from the full name rather than guessed silently at save
  // time, so a wrong guess is visible and correctable before it reaches a circular.
  // Once the field holds anything other than our own suggestion, we stop touching it.
  const suggested = useRef('');
  const [wasSuggested, setWasSuggested] = useState(false);

  const suggestGreeting = (fullName: string) => {
    const current = (form.getFieldValue('greetingName') ?? '').trim();
    if (current && current !== suggested.current) return; // the user typed their own
    const next = resolveGreeting(fullName);
    suggested.current = next;
    setWasSuggested(next.length > 0);
    form.setFieldValue('greetingName', next);
  };

  useEffect(() => {
    if (open) {
      form.resetFields();
      suggested.current = '';
      setWasSuggested(false);
      if (editing) {
        form.setFieldsValue({
          fullName: editing.fullName,
          title: editing.title,
          greetingName: editing.greetingName,
          companyId: editing.companyId,
          notes: editing.notes,
        });
      } else if (defaults) {
        form.setFieldsValue(defaults);
      }
    }
  }, [open, editing, defaults, form]);

  const submit = (values: PersonRequest) => {
    // Last line of defence: the field can still be empty if it was cleared by hand.
    const body: PersonRequest = {
      ...values,
      greetingName: values.greetingName?.trim() || resolveGreeting(values.fullName) || undefined,
    };
    if (editing) {
      update.mutate({ id: editing.id, body }, { onSuccess: onClose });
    } else {
      create.mutate(body, {
        onSuccess: (person) => {
          onCreated?.(person);
          onClose();
        },
      });
    }
  };

  return (
    <Modal
      open={open}
      title={editing ? `Edit person — ${editing.fullName}` : 'New person'}
      okText="Save"
      confirmLoading={create.isPending || update.isPending}
      onCancel={onClose}
      onOk={() => form.submit()}
      destroyOnClose
    >
      <Form form={form} layout="vertical" onFinish={submit}>
        <Form.Item name="fullName" label="Full name" rules={[{ required: true, message: 'fullName is required' }]}>
          <Input onChange={(e) => suggestGreeting(e.target.value)} />
        </Form.Item>
        <Form.Item name="title" label="Title" tooltip="Honorific shown before the greeting name (e.g. Mr., Capt.)">
          <AutoComplete options={TITLE_OPTIONS} allowClear placeholder="e.g. Mr." filterOption />
        </Form.Item>
        <Form.Item
          name="greetingName"
          label="Greeting name"
          tooltip="English first name used to greet this person in circulation emails"
          extra={
            wasSuggested ? (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                Suggested from the full name — edit it if that is not how you would greet them.
              </Typography.Text>
            ) : undefined
          }
        >
          <Input
            placeholder="e.g. Sergey"
            onChange={() => setWasSuggested(false)}
          />
        </Form.Item>
        <Form.Item name="companyId" label="Company">
          <CompanySelect allowClear />
        </Form.Item>
        <Form.Item name="notes" label="Notes">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
