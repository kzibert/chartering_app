import { Form, Input, Modal } from 'antd';
import { useEffect } from 'react';
import type { ConfirmRequest } from '../api/types';

interface Props {
  open: boolean;
  title?: string;
  loading?: boolean;
  onCancel: () => void;
  onSubmit: (body: ConfirmRequest) => void;
}

/** Captures confirmedBy + confirmNotes when marking something confirmed up to date. */
export default function ConfirmModal({ open, title, loading, onCancel, onSubmit }: Props) {
  const [form] = Form.useForm<ConfirmRequest>();

  useEffect(() => {
    if (open) form.resetFields();
  }, [open, form]);

  return (
    <Modal
      open={open}
      title={title ?? 'Confirm up to date'}
      okText="Confirm"
      confirmLoading={loading}
      onCancel={onCancel}
      onOk={() => form.submit()}
      destroyOnClose
    >
      <Form form={form} layout="vertical" onFinish={onSubmit}>
        <Form.Item name="confirmedBy" label="Confirmed by">
          <Input placeholder="your name" />
        </Form.Item>
        <Form.Item name="confirmNotes" label="Notes">
          <Input.TextArea rows={3} placeholder="e.g. called, still active on Black Sea trade" />
        </Form.Item>
      </Form>
    </Modal>
  );
}
