import { App, Button, Card, Empty, Popconfirm, Space, Table, Typography } from 'antd';
import { CopyOutlined, DownloadOutlined, DeleteOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import * as XLSX from 'xlsx';
import { useEmailList } from '../../emailList/store';
import type { EmailListEntry } from '../../api/types';

// Text columns the user can tweak inline (handy before a mail-merge); ids stay read-only.
type EditableField = 'email' | 'title' | 'greetingName' | 'personName' | 'companyName';

export default function EmailListPage() {
  const { entries, update, remove, clear } = useEmailList();
  const { message } = App.useApp();

  const editableCell =
    (field: EditableField) => (value: string | undefined, row: EmailListEntry) => (
      <Typography.Text
        editable={{
          tooltip: 'Click to edit',
          onChange: (v) => update(row.contactId, { [field]: v.trim() } as Partial<EmailListEntry>),
        }}
      >
        {value ?? ''}
      </Typography.Text>
    );

  const columns: ColumnsType<EmailListEntry> = [
    { title: 'Email', dataIndex: 'email', render: editableCell('email') },
    { title: 'Title', dataIndex: 'title', width: 80, render: editableCell('title') },
    { title: 'Greeting', dataIndex: 'greetingName', width: 130, render: editableCell('greetingName') },
    { title: 'Person', dataIndex: 'personName', width: 180, render: editableCell('personName') },
    { title: 'Company', dataIndex: 'companyName', width: 200, render: editableCell('companyName') },
    {
      title: 'Refs',
      key: 'ids',
      width: 150,
      render: (_, r) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          c{r.contactId}
          {r.personId != null ? ` · p${r.personId}` : ''}
          {r.companyId != null ? ` · co${r.companyId}` : ''}
        </Typography.Text>
      ),
    },
    {
      title: '',
      key: 'action',
      width: 48,
      render: (_, r) => (
        <Button
          type="text"
          danger
          size="small"
          icon={<DeleteOutlined />}
          aria-label="Remove from list"
          onClick={() => remove(r.contactId)}
        />
      ),
    },
  ];

  const copyEmails = async () => {
    const text = entries.map((e) => e.email).join(', ');
    try {
      await navigator.clipboard.writeText(text);
      message.success(`Copied ${entries.length} email${entries.length === 1 ? '' : 's'}`);
    } catch {
      message.error('Clipboard unavailable — copy the export instead');
    }
  };

  const exportXlsx = () => {
    const rows = entries.map((e) => ({
      Email: e.email,
      Title: e.title ?? '',
      'Greeting name': e.greetingName ?? '',
      'Person name': e.personName ?? '',
      Company: e.companyName ?? '',
      'Contact ID': e.contactId,
      'Person ID': e.personId ?? '',
      'Company ID': e.companyId ?? '',
    }));
    const ws = XLSX.utils.json_to_sheet(rows);
    ws['!cols'] = [
      { wch: 34 }, { wch: 8 }, { wch: 16 }, { wch: 22 },
      { wch: 28 }, { wch: 10 }, { wch: 10 }, { wch: 10 },
    ];
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, ws, 'Emails');
    const stamp = new Date().toISOString().slice(0, 10);
    XLSX.writeFile(wb, `email-list-${stamp}.xlsx`);
    message.success(`Exported ${entries.length} row${entries.length === 1 ? '' : 's'}`);
  };

  const empty = entries.length === 0;

  return (
    <Card
      title={`Email list (${entries.length})`}
      extra={
        <Space wrap>
          <Button icon={<CopyOutlined />} onClick={copyEmails} disabled={empty}>
            Copy all emails
          </Button>
          <Button type="primary" icon={<DownloadOutlined />} onClick={exportXlsx} disabled={empty}>
            Export XLSX
          </Button>
          <Popconfirm title="Clear the whole list?" onConfirm={clear} disabled={empty}>
            <Button danger icon={<DeleteOutlined />} disabled={empty}>
              Clear
            </Button>
          </Popconfirm>
        </Space>
      }
    >
      {empty ? (
        <Empty description="No emails yet. Add them with the + button on the Contacts tab, or from any company / vessel-owner contact list." />
      ) : (
        <Table<EmailListEntry>
          rowKey="contactId"
          size="small"
          columns={columns}
          dataSource={entries}
          pagination={false}
          scroll={{ x: true }}
        />
      )}
    </Card>
  );
}
