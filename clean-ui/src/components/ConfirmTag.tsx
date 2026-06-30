import { useState } from 'react';
import { Button, Space, Tag, Tooltip } from 'antd';
import { CheckCircleTwoTone, ClockCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import ConfirmModal from './ConfirmModal';
import type { ConfirmRequest } from '../api/types';

interface Props {
  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  loading?: boolean;
  onConfirm: (body: ConfirmRequest) => void;
  onUnconfirm: () => void;
}

/** Status tag + inline confirm/unconfirm control for any entity with a confirm block. */
export default function ConfirmTag({
  confirmed,
  confirmedAt,
  confirmedBy,
  loading,
  onConfirm,
  onUnconfirm,
}: Props) {
  const [open, setOpen] = useState(false);

  return (
    <Space size="small">
      {confirmed ? (
        <Tooltip
          title={`${confirmedBy ?? 'unknown'}${
            confirmedAt ? ' • ' + dayjs(confirmedAt).format('YYYY-MM-DD HH:mm') : ''
          }`}
        >
          <Tag icon={<CheckCircleTwoTone twoToneColor="#52c41a" />} color="success">
            Confirmed
          </Tag>
        </Tooltip>
      ) : (
        <Tag icon={<ClockCircleOutlined />} color="warning">
          Needs confirm
        </Tag>
      )}
      {confirmed ? (
        <Button size="small" type="link" loading={loading} onClick={onUnconfirm}>
          unconfirm
        </Button>
      ) : (
        <Button size="small" type="link" loading={loading} onClick={() => setOpen(true)}>
          confirm
        </Button>
      )}
      <ConfirmModal
        open={open}
        loading={loading}
        onCancel={() => setOpen(false)}
        onSubmit={(body) => {
          setOpen(false);
          onConfirm(body);
        }}
      />
    </Space>
  );
}
