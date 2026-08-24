import { useEffect, useState } from 'react';
import { Checkbox, Col, Form, Input, Modal, Row, Space } from 'antd';
import { useCompanyMutations } from '../../api/hooks';
import RecordActions from '../../components/RecordActions';
import type { CompanyRequest, CompanyResponse } from '../../api/types';

interface Props {
  open: boolean;
  editing?: CompanyResponse | null;
  onClose: () => void;
  /**
   * The company was deleted from in here. The form closes itself either way; this is for a
   * caller that also has a drawer open on the same company, which would otherwise be left
   * showing a record the server no longer has.
   */
  onDeleted?: () => void;
}

export default function CompanyForm({ open, editing, onClose, onDeleted }: Props) {
  const [form] = Form.useForm<CompanyRequest>();
  const { create, update, remove, confirm, ban } = useCompanyMutations();

  /**
   * The record as the server last described it — see the same field on VesselForm. The
   * `editing` prop is a snapshot of the clicked row and does not move when the flags do.
   */
  const [record, setRecord] = useState<CompanyResponse | null>(editing ?? null);
  useEffect(() => {
    if (open) setRecord(editing ?? null);
  }, [open, editing]);

  useEffect(() => {
    if (open) {
      if (editing) {
        form.setFieldsValue({
          name: editing.name,
          shipowner: editing.shipowner,
          solo: editing.solo,
          charterer: editing.charterer,
          broker: editing.broker,
          agent: editing.agent,
          cityName: editing.cityName,
          country: editing.country,
          website: editing.website,
          notes: editing.notes,
        });
      } else {
        form.resetFields();
      }
    }
  }, [open, editing, form]);

  const submit = (values: CompanyRequest) => {
    const done = { onSuccess: onClose };
    if (editing) update.mutate({ id: editing.id, body: values }, done);
    else create.mutate(values, done);
  };

  return (
    <Modal
      open={open}
      title={editing ? `Edit company — ${editing.name}` : 'New company'}
      okText="Save"
      confirmLoading={create.isPending || update.isPending}
      onCancel={onClose}
      onOk={() => form.submit()}
      destroyOnClose
    >
      <Form form={form} layout="vertical" onFinish={submit}>
        <Form.Item name="name" label="Name" rules={[{ required: true, message: 'name is required' }]}>
          <Input />
        </Form.Item>
        {/* City and country together: they are one answer to "where are they", and a
            country on its own line reads as a separate subject. */}
        <Row gutter={12}>
          <Col xs={12}>
            <Form.Item name="cityName" label="City">
              <Input />
            </Form.Item>
          </Col>
          <Col xs={12}>
            <Form.Item name="country" label="Country">
              <Input maxLength={100} />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item
          name="website"
          label="Website"
          tooltip="Stored without the https:// — it is added when the address is turned into a link."
        >
          <Input maxLength={255} placeholder="fednav.com" />
        </Form.Item>
        <Form.Item label="Roles">
          <Space size="large">
            <Form.Item name="shipowner" valuePropName="checked" noStyle><Checkbox>Owner</Checkbox></Form.Item>
            <Form.Item name="charterer" valuePropName="checked" noStyle><Checkbox>Charterer</Checkbox></Form.Item>
            <Form.Item name="broker" valuePropName="checked" noStyle><Checkbox>Broker</Checkbox></Form.Item>
            <Form.Item name="agent" valuePropName="checked" noStyle><Checkbox>Agent</Checkbox></Form.Item>
          </Space>
        </Form.Item>
        <Form.Item
          name="solo"
          valuePropName="checked"
          label="Solo entrepreneur"
          tooltip="One person is the whole business. Set this yourself — it is never inferred from how many contacts are on file."
        >
          <Checkbox>One-person business</Checkbox>
        </Form.Item>
        <Form.Item name="notes" label="Notes">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>

      {/* Only for a company that exists: there is nothing to confirm, ban or delete about
          one that has not been saved yet. */}
      {record && (
        <RecordActions
          entity="company"
          name={record.name}
          confirmed={record.confirmed}
          confirmedAt={record.confirmedAt}
          confirmedBy={record.confirmedBy}
          confirmLoading={confirm.isPending}
          onConfirm={(body) =>
            confirm.mutate({ id: record.id, confirmed: true, body }, { onSuccess: setRecord })
          }
          onUnconfirm={() =>
            confirm.mutate({ id: record.id, confirmed: false }, { onSuccess: setRecord })
          }
          banned={record.banned}
          banLoading={ban.isPending}
          onToggleBan={(banned) => ban.mutate({ id: record.id, banned }, { onSuccess: setRecord })}
          deleteLoading={remove.isPending}
          /*
           * Not a figure of speech: people.company_id and contacts.company_id are both
           * ON DELETE CASCADE (V1__baseline_schema.sql), so deleting a company really does
           * take its people and their addresses with it. Vessels it owns survive — that FK
           * is SET NULL — and the mail is only unlinked. Worth spelling out on the button,
           * because none of it is visible from the company row.
           */
          deleteWarning="Everyone filed under this company goes with it, and their email addresses and phone numbers with them. Vessels it owns are kept but lose their owner; past mail is kept but unlinked."
          onDelete={() =>
            remove.mutate(record.id, {
              onSuccess: () => {
                onClose();
                onDeleted?.();
              },
            })
          }
        />
      )}
    </Modal>
  );
}
