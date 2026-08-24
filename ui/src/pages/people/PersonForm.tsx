import { useEffect, useRef, useState } from 'react';
import { AutoComplete, Form, Input, Modal, Typography } from 'antd';
import { usePersonMutations } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import LeftCompanyButton from '../../components/LeftCompanyButton';
import RecordActions from '../../components/RecordActions';
import { resolveGreeting } from './resolveGreeting';
import type { PersonRequest, PersonResponse } from '../../api/types';

const TITLE_OPTIONS = ['Mr.', 'Mrs.', 'Ms.', 'Miss', 'Capt.', 'Dr.', 'Eng.', 'Prof.', 'Sir', 'Madam'].map(
  (t) => ({ value: t }),
);

/**
 * Suggestions only — the field stays free text, because the positions in this trade are not
 * a closed list and a dropdown that cannot express someone's actual job gets filled in with
 * the nearest wrong answer.
 */
const JOB_TITLE_OPTIONS = [
  'Chartering Manager',
  'Chartering Broker',
  'Chartering Officer',
  'Operations Manager',
  'Operations',
  'Managing Director',
  'General Manager',
  'Commercial Manager',
  'Fleet Manager',
  'Port Agent',
  'Owner',
  'Director',
  'Accountant',
].map((t) => ({ value: t }));

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
  /**
   * The person was deleted from in here. The form closes itself either way; this is for a
   * caller with a drawer open on the same person, which would otherwise be left showing a
   * record the server no longer has.
   */
  onDeleted?: () => void;
}

export default function PersonForm({
  open,
  editing,
  defaults,
  onCreated,
  onClose,
  onDeleted,
}: Props) {
  const [form] = Form.useForm<PersonRequest>();
  const { create, update, remove } = usePersonMutations();

  /**
   * The person as the server last described them. The `editing` prop is a snapshot of the
   * row that was clicked and does not move when the left-the-company flag does — the same
   * reason CompanyForm and VesselForm keep one of these.
   */
  const [record, setRecord] = useState<PersonResponse | null>(editing ?? null);
  useEffect(() => {
    if (open) setRecord(editing ?? null);
  }, [open, editing]);

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
          jobTitle: editing.jobTitle,
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
        {/* Deliberately next to Title, which is the honorific — seeing the two fields side
            by side is the clearest statement that they are different things. */}
        <Form.Item
          name="jobTitle"
          label="Job title"
          tooltip="The position held at the company — e.g. Chartering Manager. Shown on every address and number of theirs; not the honorific above."
          rules={[{ max: 120, message: 'Job title must be at most 120 characters' }]}
        >
          <AutoComplete options={JOB_TITLE_OPTIONS} allowClear placeholder="e.g. Chartering Manager" filterOption />
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

      {/* Only for a person who exists: there is nothing to delete about one that has not
          been saved yet, and nowhere for them to have left from. */}
      {record && (
        <RecordActions
          entity="person"
          name={record.fullName}
          deleteLoading={remove.isPending}
          /*
           * contacts.person_id is ON DELETE CASCADE (V1__baseline_schema.sql), so their
           * addresses really do go with them — which is the reason the left-the-company
           * toggle sits next to this one rather than below it. Mail and past circulations
           * keep their rows and only lose the link (SET NULL, and the run recipients hold
           * plain ids), so the history stays readable.
           */
          deleteWarning="Every email address and phone number of theirs goes too. Mail already synced and past circulations are kept, but stop pointing at them."
          onDelete={() =>
            remove.mutate(record.id, {
              onSuccess: () => {
                onClose();
                onDeleted?.();
              },
            })
          }
        >
          {/* Not a delete, and the confirmation says what it is instead: the record stays,
              the mail stops. It belongs beside Delete because they are the two ways a name
              leaves a circulation, and the milder one should be the one in reach. */}
          <LeftCompanyButton p={record} size="middle" type="default" onChanged={setRecord} />
        </RecordActions>
      )}
    </Modal>
  );
}
