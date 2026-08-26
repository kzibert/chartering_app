import { Col, DatePicker, Form, Input, Modal, Row, Typography } from 'antd';
import dayjs from 'dayjs';
import { useAnalysisMutations } from '../../analysis/store';

/**
 * One email added by hand.
 *
 * Worth having even though capture from the mailbox is the normal route: a machine working
 * offline has no IMAP configured and would otherwise have no way to start a corpus at all,
 * and an example somebody forwarded in a chat has no message here to capture from.
 */
export default function PasteSampleModal({
  open,
  onClose,
  onCreated,
}: {
  open: boolean;
  onClose: () => void;
  /** Opens the new sample for labelling — pasting one is already saying you mean to use it. */
  onCreated: (id: number) => void;
}) {
  const [form] = Form.useForm();
  const { paste } = useAnalysisMutations();

  const submit = async (values: Record<string, unknown>) => {
    const { receivedAt, ...rest } = values as { receivedAt?: dayjs.Dayjs };
    const created = await paste.mutateAsync({
      ...(rest as { bodyText: string }),
      receivedAt: receivedAt?.toISOString(),
    });
    form.resetFields();
    onClose();
    onCreated(created.sample.id);
  };

  return (
    <Modal
      open={open}
      onCancel={onClose}
      onOk={form.submit}
      okText="Add and label"
      confirmLoading={paste.isPending}
      title="Paste an email into the corpus"
      width={720}
      destroyOnClose
    >
      <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
        Paste the plain text, not a screenshot and not the HTML. What goes in here is exactly
        what the model will be trained on.
      </Typography.Paragraph>
      <Form form={form} layout="vertical" onFinish={submit} preserve={false}>
        <Row gutter={12}>
          <Col xs={24} md={10}>
            <Form.Item name="fromAddress" label="From">
              <Input placeholder="broker@example.com" />
            </Form.Item>
          </Col>
          <Col xs={24} md={8}>
            <Form.Item name="fromName" label="Sender name">
              <Input />
            </Form.Item>
          </Col>
          <Col xs={24} md={6}>
            <Form.Item
              name="receivedAt"
              label="Received"
              tooltip="Defaults to now. This is provenance — a corpus is reasoned about by the period it covers."
            >
              <DatePicker showTime style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item name="subject" label="Subject">
          <Input placeholder="Often carries the whole offer — worth keeping" />
        </Form.Item>
        <Form.Item
          name="bodyText"
          label="Email text"
          rules={[{ required: true, message: 'The email text is the sample.' }]}
        >
          <Input.TextArea rows={14} style={{ fontFamily: 'monospace', fontSize: 12.5 }} />
        </Form.Item>
        <Form.Item name="notes" label="Notes">
          <Input placeholder="Why this one is worth having. For you — never exported." />
        </Form.Item>
      </Form>
    </Modal>
  );
}
