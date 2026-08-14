import { useEffect, useState } from 'react';
import {
  App,
  Button,
  Card,
  Empty,
  Modal,
  Popconfirm,
  Segmented,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import {
  CopyOutlined,
  DownloadOutlined,
  DeleteOutlined,
  SaveOutlined,
  EditOutlined,
  ImportOutlined,
  PlusOutlined,
  MinusOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import * as XLSX from 'xlsx';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { circulationListsApi } from '../../api/circulations';
import {
  useCurrentList,
  useSavedLists,
  useCirculationList,
  useListMutations,
  listKeys,
} from '../../circulations/store';
import type { CirculationListEntry } from '../../api/types';

/** Text columns the user can tweak inline (handy before a mail-merge); ids stay read-only. */
type EditableField = 'email' | 'title' | 'greetingName' | 'personName' | 'companyName';

/** The current (draft) list is addressed by this sentinel rather than by its id. */
const CURRENT = 'current';

/**
 * Circulation lists: the prepared, reusable recipient sets, plus the current list the
 * entity tabs collect into. Picking a list here shows its addresses; editing a row edits
 * the list only, never the underlying contact.
 */
export default function CirculationListsPage() {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const current = useCurrentList();
  const savedLists = useSavedLists();
  const { rename, remove, create } = useListMutations();

  const [selected, setSelected] = useState<string | number>(CURRENT);
  const [nameModal, setNameModal] = useState<{ mode: 'saveAs' | 'rename' | 'new' } | null>(null);
  const [draftName, setDraftName] = useState('');
  const [pickedIds, setPickedIds] = useState<number[]>([]);

  const viewingCurrent = selected === CURRENT;
  const savedQuery = useCirculationList(viewingCurrent ? undefined : (selected as number));
  const list = viewingCurrent ? current.list : savedQuery.data;
  const entries = list?.entries ?? [];
  const listId = list?.id;

  // Ticks belong to the list on screen; carrying them to the next list would mean acting
  // on rows the user can no longer see.
  useEffect(() => setPickedIds([]), [selected]);

  /**
   * What the current-list actions operate on: the ticked rows, or the whole list when
   * nothing is ticked. That is what makes one button cover "add all" and "add only these"
   * without a second control to explain.
   */
  const picked = pickedIds.length ? entries.filter((e) => pickedIds.includes(e.id)) : entries;
  const scopeLabel = pickedIds.length ? `${pickedIds.length} selected` : `all ${entries.length}`;

  // Row edits work the same whichever list is on screen, so they go straight at the list
  // being viewed rather than through the current-list hook. Invalidating the whole prefix
  // keeps the entry counts in the picker honest as well as the table.
  const invalidateLists = () => qc.invalidateQueries({ queryKey: listKeys.all });

  const removeEntry = useMutation({
    mutationFn: (entryId: number) => circulationListsApi.removeEntry(listId!, entryId),
    onSuccess: invalidateLists,
  });

  const updateEntry = useMutation({
    mutationFn: (v: { entryId: number; body: CirculationListEntry }) =>
      circulationListsApi.updateEntry(listId!, v.entryId, v.body),
    onSuccess: invalidateLists,
  });

  const updateField = (row: CirculationListEntry, field: EditableField, value: string) =>
    updateEntry.mutate({ entryId: row.id, body: { ...row, [field]: value.trim() } });

  // ---- moving addresses between a saved list and the current one --------------------
  // Both act on `picked`, and both leave the saved list untouched: it is the reusable
  // source, and the current list is the scratch surface that gets sent.

  const addToCurrent = useMutation({
    mutationFn: () =>
      circulationListsApi.addEntries(
        current.listId!,
        // The entry's own id belongs to the source list, so it is dropped; everything
        // else is copied across, mail-merge edits included.
        picked.map(({ id: _id, ...fields }) => fields),
      ),
    onSuccess: (r) => {
      message.success(
        `Added ${r.added} address${r.added === 1 ? '' : 'es'} to the current list` +
          (r.skipped ? ` (${r.skipped} already there)` : ''),
      );
      invalidateLists();
    },
  });

  const removeFromCurrent = useMutation({
    mutationFn: () =>
      circulationListsApi.removeEntriesByEmail(
        current.listId!,
        picked.map((e) => e.email),
      ),
    onSuccess: (r) => {
      message.success(
        `Removed ${r.removed} address${r.removed === 1 ? '' : 'es'} from the current list` +
          (r.notOnList ? ` (${r.notOnList} were not on it)` : ''),
      );
      invalidateLists();
    },
  });

  const editableCell =
    (field: EditableField) => (value: string | undefined, row: CirculationListEntry) => (
      <Typography.Text
        editable={{
          tooltip: 'Click to edit — changes this list only, not the contact record',
          onChange: (v) => updateField(row, field, v),
        }}
      >
        {value ?? ''}
      </Typography.Text>
    );

  const columns: ColumnsType<CirculationListEntry> = [
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
          {r.contactId != null ? `c${r.contactId}` : 'manual'}
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
          onClick={() => removeEntry.mutate(r.id)}
        />
      ),
    },
  ];

  const copyEmails = async () => {
    try {
      await navigator.clipboard.writeText(entries.map((e) => e.email).join(', '));
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
      'Contact ID': e.contactId ?? '',
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
    const slug = (list?.name ?? 'current-list').toLowerCase().replace(/[^a-z0-9]+/g, '-');
    XLSX.writeFile(wb, `${slug}-${new Date().toISOString().slice(0, 10)}.xlsx`);
    message.success(`Exported ${entries.length} row${entries.length === 1 ? '' : 's'}`);
  };

  const submitName = async () => {
    const name = draftName.trim();
    if (!name) return;
    try {
      if (nameModal?.mode === 'saveAs') {
        const saved = await current.saveAs.mutateAsync({ name });
        message.success(`Saved as "${name}"`);
        setSelected(saved.id);
      } else if (nameModal?.mode === 'rename') {
        await rename.mutateAsync({ id: listId!, name });
        message.success(`Renamed to "${name}"`);
      } else {
        const created = await create.mutateAsync({ name });
        message.success(`Created "${name}"`);
        setSelected(created.id);
      }
      setNameModal(null);
    } catch {
      /* the axios interceptor surfaces the error */
    }
  };

  /** Load a saved list into the current one, so it can be sent or added to. */
  const loadIntoCurrent = () => {
    current.load.mutate(listId!, {
      onSuccess: () => {
        message.success(`Current list replaced with "${list?.name}"`);
        setSelected(CURRENT);
      },
    });
  };

  const openNameModal = (mode: 'saveAs' | 'rename' | 'new') => {
    setDraftName(mode === 'rename' ? (list?.name ?? '') : '');
    setNameModal({ mode });
  };

  const empty = entries.length === 0;

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Card size="small">
        <Space wrap align="center">
          <Segmented
            value={selected}
            onChange={(v) => setSelected(v as string | number)}
            options={[
              {
                value: CURRENT,
                label: `Current list (${current.entries.length})`,
              },
              ...(savedLists.data ?? []).map((l) => ({
                value: l.id,
                label: `${l.name} (${l.entryCount})`,
              })),
            ]}
          />
          <Button size="small" icon={<PlusOutlined />} onClick={() => openNameModal('new')}>
            New list
          </Button>
        </Space>
      </Card>

      <Card
        title={
          <Space>
            {viewingCurrent ? 'Current list' : list?.name}
            <Tag>{entries.length}</Tag>
            {viewingCurrent && (
              <Tooltip title="This is what the Circulars tab sends to, and what the other tabs add into by default">
                <Tag color="blue">used for sending</Tag>
              </Tooltip>
            )}
          </Space>
        }
        extra={
          <Space wrap>
            {viewingCurrent ? (
              <Tooltip title="Copy these addresses into a new named list — the current list keeps them">
                <Button icon={<SaveOutlined />} disabled={empty} onClick={() => openNameModal('saveAs')}>
                  Save as list
                </Button>
              </Tooltip>
            ) : (
              <>
                <Tooltip
                  title={`Add ${scopeLabel} of this list's addresses to the current list. This list is not changed.`}
                >
                  <Button
                    type="primary"
                    ghost
                    icon={<PlusOutlined />}
                    loading={addToCurrent.isPending}
                    disabled={empty}
                    onClick={() => addToCurrent.mutate()}
                  >
                    Add {scopeLabel} to current
                  </Button>
                </Tooltip>
                <Tooltip
                  title={`Take ${scopeLabel} of this list's addresses off the current list — for excluding people you have already circulated to. This list is not changed.`}
                >
                  <Button
                    icon={<MinusOutlined />}
                    loading={removeFromCurrent.isPending}
                    disabled={empty}
                    onClick={() => removeFromCurrent.mutate()}
                  >
                    Remove {scopeLabel} from current
                  </Button>
                </Tooltip>
                <Tooltip title="Discard the current list and replace it with this one, ready to send">
                  <Button
                    icon={<ImportOutlined />}
                    loading={current.load.isPending}
                    disabled={empty}
                    onClick={loadIntoCurrent}
                  >
                    Replace current
                  </Button>
                </Tooltip>
                <Button icon={<EditOutlined />} onClick={() => openNameModal('rename')}>
                  Rename
                </Button>
              </>
            )}
            <Button icon={<CopyOutlined />} onClick={copyEmails} disabled={empty}>
              Copy all emails
            </Button>
            <Button type="primary" icon={<DownloadOutlined />} onClick={exportXlsx} disabled={empty}>
              Export XLSX
            </Button>
            {viewingCurrent ? (
              <Popconfirm
                title="Clear the current list?"
                onConfirm={() => current.clear.mutate()}
                disabled={empty}
              >
                <Button danger icon={<DeleteOutlined />} disabled={empty}>
                  Clear
                </Button>
              </Popconfirm>
            ) : (
              <Popconfirm
                title={`Delete "${list?.name}"?`}
                description="The addresses on it are removed with it. Past circulations are unaffected."
                onConfirm={() =>
                  remove.mutate(listId!, {
                    onSuccess: () => {
                      message.success('List deleted');
                      setSelected(CURRENT);
                    },
                  })
                }
              >
                <Button danger icon={<DeleteOutlined />}>
                  Delete list
                </Button>
              </Popconfirm>
            )}
          </Space>
        }
      >
        {empty ? (
          <Empty
            description={
              viewingCurrent
                ? 'No addresses yet. Add them from the Companies, Vessels or People tabs, or with the + button on any contact.'
                : 'This list is empty. Add addresses to it from any entity tab.'
            }
          />
        ) : (
          <Table<CirculationListEntry>
            rowKey="id"
            size="small"
            loading={viewingCurrent ? current.isLoading : savedQuery.isLoading}
            columns={columns}
            dataSource={entries}
            pagination={entries.length > 50 ? { pageSize: 50, showSizeChanger: false } : false}
            scroll={{ x: true }}
            // Only on a saved list: the ticks exist to scope the add/remove buttons above,
            // and the current list has no such buttons to scope.
            rowSelection={
              viewingCurrent
                ? undefined
                : {
                    selectedRowKeys: pickedIds,
                    preserveSelectedRowKeys: true,
                    onChange: (keys) => setPickedIds(keys as number[]),
                  }
            }
          />
        )}
      </Card>

      <Modal
        open={nameModal != null}
        onCancel={() => setNameModal(null)}
        onOk={submitName}
        okText="Save"
        okButtonProps={{ disabled: !draftName.trim() }}
        title={
          nameModal?.mode === 'rename'
            ? 'Rename this list'
            : nameModal?.mode === 'saveAs'
              ? 'Save the current list under a name'
              : 'Create an empty list'
        }
      >
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          {nameModal?.mode === 'saveAs'
            ? 'The current list keeps its addresses — this takes a copy you can come back to.'
            : 'Names must be unique; they are how lists are picked when adding contacts.'}
        </Typography.Paragraph>
        <input
          autoFocus
          className="ant-input"
          style={{ width: '100%', padding: '4px 11px' }}
          placeholder="e.g. Handysize owners — Med"
          value={draftName}
          maxLength={150}
          onChange={(e) => setDraftName(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && submitName()}
        />
      </Modal>
    </Space>
  );
}
