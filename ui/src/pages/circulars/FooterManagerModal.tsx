import { useState } from 'react';
import {
  App,
  Button,
  Empty,
  Input,
  Modal,
  Popconfirm,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
} from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { emailFootersApi } from '../../api/emailLibrary';
import RichTextEditor from '../../components/RichTextEditor';
import type { EmailFooterResponse } from '../../api/types';

interface Props {
  open: boolean;
  onClose: () => void;
}

const BLANK = { id: null as number | null, name: '', html: '', defaultFooter: false };

/** Create/edit/delete the reusable signature blocks appended to circulars. */
export default function FooterManagerModal({ open, onClose }: Props) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const [draft, setDraft] = useState<typeof BLANK>(BLANK);
  const [editing, setEditing] = useState(false);

  const footersQ = useQuery({
    queryKey: ['email-footers'],
    queryFn: emailFootersApi.list,
    enabled: open,
  });

  const done = () => {
    qc.invalidateQueries({ queryKey: ['email-footers'] });
    setEditing(false);
    setDraft(BLANK);
  };

  const saveMut = useMutation({
    mutationFn: () => {
      const body = { name: draft.name, html: draft.html, defaultFooter: draft.defaultFooter };
      return draft.id == null
        ? emailFootersApi.create(body)
        : emailFootersApi.update(draft.id, body);
    },
    onSuccess: () => {
      message.success('Footer saved');
      done();
    },
  });

  const deleteMut = useMutation({
    mutationFn: (id: number) => emailFootersApi.remove(id),
    onSuccess: () => {
      message.success('Footer deleted');
      done();
    },
  });

  const startEdit = (f: EmailFooterResponse) => {
    setDraft({ id: f.id, name: f.name, html: f.html, defaultFooter: f.defaultFooter });
    setEditing(true);
  };

  const columns: ColumnsType<EmailFooterResponse> = [
    {
      title: 'Name',
      dataIndex: 'name',
      render: (name: string, f) => (
        <Space>
          {name}
          {f.defaultFooter && <Tag color="blue">default</Tag>}
        </Space>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 110,
      render: (_, f) => (
        <Space size={0}>
          <Button type="text" size="small" icon={<EditOutlined />} onClick={() => startEdit(f)} />
          <Popconfirm title={`Delete "${f.name}"?`} onConfirm={() => deleteMut.mutate(f.id)}>
            <Button type="text" size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Modal
      open={open}
      onCancel={() => {
        setEditing(false);
        setDraft(BLANK);
        onClose();
      }}
      footer={null}
      width={820}
      title="Footers"
      destroyOnClose
    >
      {editing ? (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Input
            placeholder="Footer name, e.g. Full signature"
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            maxLength={150}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Use the <b>HTML</b> button to paste designed markup. Mail-merge placeholders work
            here too — <code>{'{{company}}'}</code>, <code>{'{{email}}'}</code>, and the rest.
          </Typography.Text>
          <RichTextEditor
            value={draft.html}
            onChange={(html) => setDraft({ ...draft, html })}
            minHeight={180}
          />
          <Space>
            <Switch
              checked={draft.defaultFooter}
              onChange={(v) => setDraft({ ...draft, defaultFooter: v })}
            />
            <span>Pre-select this footer on the compose tab</span>
          </Space>
          <Space>
            <Button
              type="primary"
              loading={saveMut.isPending}
              disabled={!draft.name.trim() || !draft.html.trim()}
              onClick={() => saveMut.mutate()}
            >
              Save
            </Button>
            <Button
              onClick={() => {
                setEditing(false);
                setDraft(BLANK);
              }}
            >
              Cancel
            </Button>
          </Space>
        </Space>
      ) : (
        <Space direction="vertical" size="middle" style={{ width: '100%' }}>
          <Button
            icon={<PlusOutlined />}
            onClick={() => {
              setDraft(BLANK);
              setEditing(true);
            }}
          >
            New footer
          </Button>
          {footersQ.data && footersQ.data.length > 0 ? (
            <Table<EmailFooterResponse>
              rowKey="id"
              size="small"
              loading={footersQ.isLoading}
              columns={columns}
              dataSource={footersQ.data}
              pagination={false}
              scroll={{ x: true }}
            />
          ) : (
            <Empty description="No footers yet" />
          )}
        </Space>
      )}
    </Modal>
  );
}
