import { App, Button, Card, Col, Form, Input, Popconfirm, Row, Segmented, Space, Tag, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import ResponsiveTable from '../../components/ResponsiveTable';
import PortSelect from '../../components/PortSelect';
import TradeAreaSelect from '../../components/TradeAreaSelect';
import { vocabularyApi, type AliasKind, type DeskAlias } from '../../api/vocabulary';
import { useSession } from '../../auth/session';
import { roleAtLeast } from '../../api/auth';

interface FormValues {
  kind: AliasKind;
  targetId?: number;
  alias: string;
}

/**
 * The desk's own spellings of ports and trade areas.
 *
 * The parser reads a circular's "load CHORNO" with a vocabulary every desk shares — the
 * market's spellings, which arrive with updates. Each desk also has correspondents with habits
 * of their own; a spelling added here is read that way for this desk only, and where it
 * clashes with a shared one, this desk's reading wins. A port's own name always beats any
 * alias.
 */
export default function DeskVocabularyCard() {
  const { message: toast } = App.useApp();
  const qc = useQueryClient();
  const session = useSession();
  const canEdit = roleAtLeast(session.role, 'TENANT_ADMIN');
  const [form] = Form.useForm<FormValues>();
  const kind = Form.useWatch('kind', form) ?? 'PORT';

  const aliases = useQuery({ queryKey: ['vocabulary', 'aliases'], queryFn: vocabularyApi.aliases });
  const refresh = () => qc.invalidateQueries({ queryKey: ['vocabulary'] });

  const add = useMutation({
    mutationFn: (v: FormValues) => vocabularyApi.add({ kind: v.kind, targetId: v.targetId!, alias: v.alias }),
    onSuccess: (a) => {
      toast.success(`"${a.alias}" is now read as ${a.targetName}`);
      form.setFieldsValue({ alias: '', targetId: undefined });
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (a: DeskAlias) => vocabularyApi.remove(a.kind, a.id),
    onSuccess: refresh,
  });

  const kindTag = (k: AliasKind) => (k === 'PORT' ? <Tag color="blue">Port</Tag> : <Tag color="purple">Trade area</Tag>);

  return (
    <Card title="Desk vocabulary" loading={aliases.isLoading}>
      <Typography.Paragraph type="secondary">
        Spellings this desk's correspondents use for a port or a sea, read that way for this desk
        only.
      </Typography.Paragraph>

      {canEdit && (
        <Form<FormValues>
          form={form}
          layout="vertical"
          initialValues={{ kind: 'PORT' }}
          onFinish={(v) => add.mutate(v)}
        >
          <Row gutter={12} align="bottom">
            <Col xs={24} md={6}>
              <Form.Item name="kind" label="Read as a">
                <Segmented
                  options={[
                    { label: 'Port', value: 'PORT' },
                    { label: 'Trade area', value: 'AREA' },
                  ]}
                  onChange={() => form.setFieldsValue({ targetId: undefined })}
                />
              </Form.Item>
            </Col>
            <Col xs={24} md={7}>
              <Form.Item name="alias" label="Spelling" rules={[{ required: true, message: 'Required' }]}>
                <Input placeholder="CHORNO" maxLength={100} />
              </Form.Item>
            </Col>
            <Col xs={24} md={7}>
              <Form.Item name="targetId" label="Means" rules={[{ required: true, message: 'Required' }]}>
                {kind === 'PORT' ? <PortSelect /> : <TradeAreaSelect />}
              </Form.Item>
            </Col>
            <Col xs={24} md={4}>
              <Form.Item>
                <Button type="primary" htmlType="submit" icon={<PlusOutlined />} loading={add.isPending} block>
                  Add
                </Button>
              </Form.Item>
            </Col>
          </Row>
        </Form>
      )}

      <ResponsiveTable<DeskAlias>
        rowKey={(a) => `${a.kind}-${a.id}`}
        size="small"
        dataSource={aliases.data ?? []}
        pagination={false}
        locale={{ emptyText: 'No spellings of the desk’s own yet' }}
        columns={[
          { title: 'Spelling', dataIndex: 'alias', key: 'alias' },
          { title: 'Read as', key: 'target', render: (_, a) => <Space>{kindTag(a.kind)}{a.targetName}</Space> },
          ...(canEdit
            ? [
                {
                  title: '',
                  key: 'actions',
                  width: 60,
                  render: (_: unknown, a: DeskAlias) => (
                    <Popconfirm title={`Stop reading "${a.alias}" as ${a.targetName}?`} onConfirm={() => remove.mutate(a)}>
                      <Button size="small" danger icon={<DeleteOutlined />} aria-label="Remove" />
                    </Popconfirm>
                  ),
                },
              ]
            : []),
        ]}
        mobile={{
          title: (a) => a.alias,
          subtitle: (a) => a.targetName,
          fields: (a) => [{ label: 'Kind', value: kindTag(a.kind) }],
          actions: (a) =>
            canEdit && (
              <Button size="small" danger icon={<DeleteOutlined />} onClick={() => remove.mutate(a)}>
                Remove
              </Button>
            ),
        }}
      />
    </Card>
  );
}
