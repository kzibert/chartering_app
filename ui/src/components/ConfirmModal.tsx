import { Form, Input, Modal, Typography } from 'antd';
import { useEffect } from 'react';
import type { ReactNode } from 'react';
import type { ConfirmRequest } from '../api/types';

interface Props {
  open: boolean;
  title?: string;
  /**
   * A line above the fields saying what is about to be attested to, and to what.
   *
   * Optional because where the button sits inside an edit form there is no doubt about
   * which record is meant — you are already editing it. On a read-only screen there is:
   * the button is one of several on a drawer, and the modal is the only thing standing
   * between a stray click and somebody's name going onto a record they never checked.
   */
  description?: ReactNode;
  loading?: boolean;
  onCancel: () => void;
  onSubmit: (body: ConfirmRequest) => void;
}

/** Captures confirmedBy + confirmNotes when marking something confirmed up to date. */
export default function ConfirmModal({ open, title, description, loading, onCancel, onSubmit }: Props) {
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
      {description && (
        <Typography.Paragraph type="secondary">{description}</Typography.Paragraph>
      )}
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
