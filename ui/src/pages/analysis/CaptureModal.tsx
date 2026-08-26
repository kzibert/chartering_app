import { useState } from 'react';
import {
  Alert,
  Checkbox,
  Col,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { useMailServerFolders, useMailFolders } from '../../mailbox/store';
import { useAnalysisMutations } from '../../analysis/store';
import type { AnalysisCaptureResponse } from '../../api/analysis';

/**
 * Choosing which synced mail to take into the corpus.
 *
 * The fields are the mailbox's own filters, on purpose: the useful capture is almost never
 * "everything" but "the Brokers folder, last quarter" or "anything from this house", and
 * asking for it in the vocabulary the user already filters mail with means there is no
 * second query language to learn. What comes back is a count rather than a list, because
 * the result of a capture is a corpus you then work through, not a set of rows to admire.
 */
export default function CaptureModal({
  open,
  onClose,
  maxPerRun,
}: {
  open: boolean;
  onClose: () => void;
  maxPerRun: number;
}) {
  const [form] = Form.useForm();
  const { capture } = useAnalysisMutations();
  const { data: serverFolders } = useMailServerFolders();
  const { data: appFolders } = useMailFolders();
  const [result, setResult] = useState<AnalysisCaptureResponse>();

  const submit = async (values: Record<string, unknown>) => {
    const { range, ...rest } = values as { range?: [dayjs.Dayjs, dayjs.Dayjs] };
    const res = await capture.mutateAsync({
      ...rest,
      receivedFrom: range?.[0]?.startOf('day').toISOString(),
      receivedTo: range?.[1]?.endOf('day').toISOString(),
    });
    // The modal stays open holding the outcome rather than closing on success. A capture is
    // the one action here whose result is a number the user has to read — "0 captured of
    // 400 matched" is either the dedupe working or a filter that caught nothing, and
    // closing the window would take that away before it had been read.
    setResult(res);
  };

  const close = () => {
    setResult(undefined);
    form.resetFields();
    onClose();
  };

  return (
    <Modal
      open={open}
      onCancel={close}
      title="Capture mail into the corpus"
      okText={result ? 'Capture more' : 'Capture'}
      onOk={result ? () => setResult(undefined) : form.submit}
      confirmLoading={capture.isPending}
      cancelText={result ? 'Done' : 'Cancel'}
      width={640}
      destroyOnClose
    >
      {result ? (
        <CaptureResult result={result} />
      ) : (
        <Form form={form} layout="vertical" onFinish={submit} preserve={false}>
          <Typography.Paragraph type="secondary" style={{ marginTop: 0 }}>
            Nothing in the mailbox is changed — no flag, no move. Everything lands unlabelled,
            and mail already in the corpus is skipped, so running this again after a sync adds
            only what is new.
          </Typography.Paragraph>

          <Row gutter={12}>
            <Col xs={24} md={12}>
              <Form.Item
                name="imapFolder"
                label="Mail server folder"
                tooltip="The folder the server keeps it in, and everything nested under it"
              >
                <Select
                  allowClear
                  showSearch
                  placeholder="Every folder"
                  optionFilterProp="label"
                  options={(serverFolders ?? [])
                    .filter((f) => f.selectable)
                    .map((f) => ({
                      value: f.fullName,
                      label: `${f.fullName} (${f.total})`,
                    }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="folderId"
                label="App folder"
                tooltip="This app's own filing — the other axis, as on the Mailbox tab"
              >
                <Select
                  allowClear
                  placeholder="Any"
                  options={(appFolders ?? [])
                    .filter((f) => f.id != null)
                    .map((f) => ({ value: f.id as number, label: `${f.name} (${f.total})` }))}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={14}>
              <Form.Item name="search" label="Contains">
                <Input allowClear placeholder="e.g. handysize, or a broker's domain" />
              </Form.Item>
            </Col>
            <Col xs={24} md={10}>
              <Form.Item
                name="searchBody"
                valuePropName="checked"
                label=" "
                tooltip="Scans every stored message body. Slow, exactly as on the Mailbox tab."
              >
                <Checkbox>Search message text too</Checkbox>
              </Form.Item>
            </Col>
            <Col xs={24} md={14}>
              <Form.Item name="range" label="Received between">
                <DatePicker.RangePicker style={{ width: '100%' }} allowEmpty={[true, true]} />
              </Form.Item>
            </Col>
            <Col xs={24} md={10}>
              <Form.Item
                name="limit"
                label="At most"
                initialValue={maxPerRun}
                tooltip={`Newest first. The server caps this at ${maxPerRun} per run; run it again to continue.`}
              >
                <InputNumber min={1} max={maxPerRun} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      )}
    </Modal>
  );
}

/**
 * What the run did, in the four numbers that tell the outcomes apart. "Captured" alone
 * cannot: nothing captured out of four hundred matched is either a folder already in the
 * corpus or a filter that found only empty messages, and those want opposite next steps.
 */
function CaptureResult({ result }: { result: AnalysisCaptureResponse }) {
  const nothingNew = result.captured === 0;
  return (
    <>
      <Alert
        type={nothingNew ? 'info' : 'success'}
        showIcon
        message={
          nothingNew
            ? 'Nothing new was captured'
            : `${result.captured} email${result.captured === 1 ? '' : 's'} added to the corpus`
        }
        description={
          <ul style={{ margin: '8px 0 0', paddingInlineStart: 18 }}>
            <li>{result.matched} matched the filter</li>
            {result.alreadyPresent > 0 && (
              <li>{result.alreadyPresent} already in the corpus, left alone</li>
            )}
            {result.skippedEmpty > 0 && (
              <li>
                {result.skippedEmpty} had no text to keep — an attachment-only position list
                or a calendar invite
              </li>
            )}
            {result.limitReached && (
              <li>
                The run stopped at its cap, newest first. Run it again to take the next batch.
              </li>
            )}
          </ul>
        }
      />
      {result.examples.length > 0 && (
        <>
          <Typography.Text strong style={{ display: 'block', marginTop: 16 }}>
            For example
          </Typography.Text>
          <ul style={{ margin: '4px 0 0', paddingInlineStart: 18 }}>
            {result.examples.map((s, i) => (
              <li key={i}>
                <Typography.Text type="secondary" ellipsis>
                  {s}
                </Typography.Text>
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}
