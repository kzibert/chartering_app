import { useEffect, useState } from 'react';
import { Alert, Descriptions, Modal, Spin, Typography } from 'antd';
import type { MailPreviewResponse } from '../api/types';

/**
 * A draft as it would go out, before it goes.
 *
 * <p>Asked of the server rather than drawn here, unlike the Circulars tab's preview: the footer,
 * the quoted original and the merge are all applied there at send time, and a copy of those
 * rules in the browser would be a second opinion about the message rather than the message. So
 * this shows the very string the send would hand the mail server — and nothing is sent.
 *
 * <p>Rendered inside the composer window, so antd lifts it above that window on its own.
 */
export default function PreviewModal({
  load,
  onClose,
}: {
  /** Fetches the preview; set while the preview is open, null while it is not. */
  load: (() => Promise<MailPreviewResponse>) | null;
  onClose: () => void;
}) {
  const [data, setData] = useState<MailPreviewResponse>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!load) return;
    setData(undefined);
    setError(undefined);
    load()
      .then(setData)
      .catch((e: unknown) =>
        setError(
          (e as { response?: { data?: { message?: string } } })?.response?.data?.message ??
            'The preview could not be built.',
        ),
      );
  }, [load]);

  return (
    <Modal open={load != null} onCancel={onClose} footer={null} width={820} title="Preview — as it will be sent">
      {error && <Alert type="error" showIcon message={error} />}
      {!data && !error && <Spin />}
      {data && (
        <>
          <Descriptions size="small" column={1} bordered style={{ marginBottom: 12 }}>
            <Descriptions.Item label="From">
              {data.fromName} <Typography.Text type="secondary">&lt;{data.fromAddress}&gt;</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="To">{data.to}</Descriptions.Item>
            {data.cc && data.cc.length > 0 && (
              <Descriptions.Item label="CC">{data.cc.join(', ')}</Descriptions.Item>
            )}
            <Descriptions.Item label="Subject">{data.subject}</Descriptions.Item>
          </Descriptions>
          {/* Sanitised on the server, the same pass the send makes. */}
          <div
            style={{
              border: '1px solid #f0f0f0',
              borderRadius: 4,
              padding: 16,
              maxHeight: '60vh',
              overflow: 'auto',
            }}
            dangerouslySetInnerHTML={{ __html: data.html }}
          />
        </>
      )}
    </Modal>
  );
}
