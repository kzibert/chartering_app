import { useEffect } from 'react';
import { Checkbox, Form, Input, Modal, Space } from 'antd';
import { useCompanyMutations } from '../../api/hooks';
import type { CompanyRequest, CompanyResponse } from '../../api/types';

interface Props {
  open: boolean;
  editing?: CompanyResponse | null;
  onClose: () => void;
}

export default function CompanyForm({ open, editing, onClose }: Props) {
  const [form] = Form.useForm<CompanyRequest>();
  const { create, update } = useCompanyMutations();

  useEffect(() => {
    if (open) {
      if (editing) {
        form.setFieldsValue({
          name: editing.name,
          shipowner: editing.shipowner,
          charterer: editing.charterer,
          broker: editing.broker,
          agent: editing.agent,
          cityName: editing.cityName,
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
        <Form.Item name="cityName" label="City">
          <Input />
        </Form.Item>
        <Form.Item label="Roles">
          <Space size="large">
            <Form.Item name="shipowner" valuePropName="checked" noStyle><Checkbox>Owner</Checkbox></Form.Item>
            <Form.Item name="charterer" valuePropName="checked" noStyle><Checkbox>Charterer</Checkbox></Form.Item>
            <Form.Item name="broker" valuePropName="checked" noStyle><Checkbox>Broker</Checkbox></Form.Item>
            <Form.Item name="agent" valuePropName="checked" noStyle><Checkbox>Agent</Checkbox></Form.Item>
          </Space>
        </Form.Item>
        <Form.Item name="notes" label="Notes">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
