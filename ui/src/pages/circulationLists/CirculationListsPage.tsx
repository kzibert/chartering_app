import { useEffect, useMemo, useState } from 'react';
import {
  App,
  Button,
  Card,
  Dropdown,
  Empty,
  Input,
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
  DownOutlined,
  SearchOutlined,
  ClearOutlined,
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
import { reportAdd } from '../../circulations/addResult';
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
  const [search, setSearch] = useState('');

  const viewingCurrent = selected === CURRENT;
  const savedQuery = useCirculationList(viewingCurrent ? undefined : (selected as number));
  const list = viewingCurrent ? current.list : savedQuery.data;
  const entries = list?.entries ?? [];
  const listId = list?.id;

  // Ticks and the search box both belong to the list on screen; carrying either to the
  // next list would mean acting on rows the user can no longer see.
  useEffect(() => {
    setPickedIds([]);
    setSearch('');
  }, [selected]);

  /**
   * Free-text narrowing over the three fields worth hunting by: the address, the person
   * and the company. Every whitespace-separated term has to match somewhere, so "john
   * maersk" finds John at Maersk rather than nothing — the fields are searched together
   * but a term is not required to land in any particular one.
   */
  const filtered = useMemo(() => {
    const terms = search.trim().toLowerCase().split(/\s+/).filter(Boolean);
    if (terms.length === 0) return entries;
    return entries.filter((e) => {
      const haystack = `${e.email} ${e.personName ?? ''} ${e.companyName ?? ''}`.toLowerCase();
      return terms.every((t) => haystack.includes(t));
    });
  }, [entries, search]);

  const filtering = filtered.length !== entries.length;

  /**
   * What the list actions operate on: the ticked rows, or — when nothing is ticked —
   * whatever the search has narrowed the list down to. Acting on the whole list while the
   * user is looking at eleven filtered rows is the one reading that would surprise them,
   * so the filter scopes the buttons and the label says which set is meant.
   */
  const picked = pickedIds.length ? entries.filter((e) => pickedIds.includes(e.id)) : filtered;
  const scopeLabel = pickedIds.length
    ? `${pickedIds.length} selected`
    : filtering
      ? `all ${filtered.length} shown`
      : `all ${entries.length}`;

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
      reportAdd(message, r, 'the current list');
      invalidateLists();
    },
  });

  /**
   * The mirror of addToCurrent, going the other way: push rows off the current list into a
   * saved one. The target has to be chosen, hence a dropdown rather than a plain button —
   * and only existing lists are offered, since "Save as list" already covers making a new
   * one out of the current list.
   */
  const addToSavedList = useMutation({
    mutationFn: (targetId: number) =>
      circulationListsApi.addEntries(
        targetId,
        picked.map(({ id: _id, ...fields }) => fields),
      ),
    onSuccess: (r, targetId) => {
      const name = savedLists.data?.find((l) => l.id === targetId)?.name ?? 'the list';
      reportAdd(message, r, `"${name}"`);
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
    {
      title: 'Email',
      dataIndex: 'email',
      // The row stays on the list — a list is a snapshot of a document you prepared — but a
      // row the send will drop has to say so, or the count at the top is a promise the
      // circular will not keep.
      render: (_, r) =>
        r.personLeft ? (
          <Space size={4} wrap>
            {editableCell('email')(r.email, r)}
            <Tooltip title={`${r.personName ?? 'This person'} has left ${r.companyName ?? 'the company'}, so this address will be skipped when the circular goes out. Remove the row, or put them back on the People tab.`}>
              <Tag color="red">left the company</Tag>
            </Tooltip>
          </Space>
        ) : (
          editableCell('email')(r.email, r)
        ),
    },
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

  // Copy and export follow the search rather than the whole list: what is on screen is
  // what the user means by "these", and quietly copying rows they have filtered away is
  // the kind of surprise that only shows up after the addresses are already pasted
  // somewhere. The counts in the labels and messages say which set was taken.
  const copyEmails = async () => {
    try {
      await navigator.clipboard.writeText(filtered.map((e) => e.email).join(', '));
      message.success(`Copied ${filtered.length} email${filtered.length === 1 ? '' : 's'}`);
    } catch {
      message.error('Clipboard unavailable — copy the export instead');
    }
  };

  const exportXlsx = () => {
    const rows = filtered.map((e) => ({
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
    message.success(`Exported ${filtered.length} row${filtered.length === 1 ? '' : 's'}`);
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
              <>
                <Tooltip
                  title={
                    savedLists.data?.length
                      ? `Copy ${scopeLabel} of these addresses into one of your saved lists. The current list keeps them.`
                      : 'No saved lists yet — use "Save as list" to make one first'
                  }
                >
                  {/* Wrapped: a disabled Dropdown swallows pointer events, so the Tooltip
                      needs its own element to hang off when there is nothing to pick. */}
                  <span>
                    <Dropdown
                      trigger={['click']}
                      disabled={picked.length === 0 || !savedLists.data?.length}
                      menu={{
                        items: (savedLists.data ?? []).map((l) => ({
                          key: String(l.id),
                          label: `${l.name} (${l.entryCount})`,
                        })),
                        onClick: ({ key }) => addToSavedList.mutate(Number(key)),
                      }}
                    >
                      <Button
                        icon={<PlusOutlined />}
                        loading={addToSavedList.isPending}
                        disabled={picked.length === 0 || !savedLists.data?.length}
                      >
                        Add {scopeLabel} to list <DownOutlined />
                      </Button>
                    </Dropdown>
                  </span>
                </Tooltip>
                <Tooltip title="Copy these addresses into a new named list — the current list keeps them">
                  <Button icon={<SaveOutlined />} disabled={empty} onClick={() => openNameModal('saveAs')}>
                    Save as list
                  </Button>
                </Tooltip>
              </>
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
                    disabled={picked.length === 0}
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
                    disabled={picked.length === 0}
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
            <Tooltip
              title={
                filtering
                  ? `Copies the ${filtered.length} address${filtered.length === 1 ? '' : 'es'} the search is showing, not the whole list`
                  : undefined
              }
            >
              <Button icon={<CopyOutlined />} onClick={copyEmails} disabled={filtered.length === 0}>
                Copy {filtering ? `${filtered.length} emails` : 'all emails'}
              </Button>
            </Tooltip>
            <Tooltip
              title={
                filtering
                  ? `Exports the ${filtered.length} row${filtered.length === 1 ? '' : 's'} the search is showing, not the whole list`
                  : undefined
              }
            >
              <Button
                type="primary"
                icon={<DownloadOutlined />}
                onClick={exportXlsx}
                disabled={filtered.length === 0}
              >
                Export XLSX
              </Button>
            </Tooltip>
            {viewingCurrent ? (
              /* Deliberately not scoped by the search: clearing is destructive, and
                 "clear" meaning "clear the eleven rows you can see" is a reading nobody
                 should have to guess at. The confirmation says so while a filter is on. */
              <Popconfirm
                title="Clear the current list?"
                description={
                  filtering
                    ? `All ${entries.length} addresses go, not just the ${filtered.length} shown.`
                    : undefined
                }
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
                description={
                  `The addresses on it are removed with it. Past circulations are unaffected.` +
                  (filtering ? ` The search does not limit this — all ${entries.length} go.` : '')
                }
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
          <Space direction="vertical" size="small" style={{ width: '100%' }}>
            <Space wrap align="center">
              <Input
                allowClear
                prefix={<SearchOutlined />}
                placeholder="Find by email, person or company"
                style={{ width: 320 }}
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
              <Tooltip title={filtering ? 'Clear the search and show every address again' : 'Nothing is filtered'}>
                {/* Wrapped so the tooltip still has an element to hang off once the
                    button is disabled — a disabled button swallows pointer events. */}
                <span>
                  <Button icon={<ClearOutlined />} disabled={!search} onClick={() => setSearch('')}>
                    Reset filter
                  </Button>
                </span>
              </Tooltip>
              {filtering && (
                <Typography.Text type="secondary">
                  Showing {filtered.length} of {entries.length}
                  {pickedIds.length > 0 && ' — the buttons above act on the ticked rows'}
                </Typography.Text>
              )}
            </Space>

            {filtered.length === 0 ? (
              <Empty description={`No address here matches "${search.trim()}".`}>
                <Button icon={<ClearOutlined />} onClick={() => setSearch('')}>
                  Show all {entries.length}
                </Button>
              </Empty>
            ) : (
              <Table<CirculationListEntry>
                rowKey="id"
                size="small"
                loading={viewingCurrent ? current.isLoading : savedQuery.isLoading}
                columns={columns}
                dataSource={filtered}
                pagination={filtered.length > 50 ? { pageSize: 50, showSizeChanger: false } : false}
                scroll={{ x: true }}
                // Both views have a button scoped by the ticks: a saved list moves rows to
                // and from the current one, the current list pushes rows into a saved one.
                rowSelection={{
                  selectedRowKeys: pickedIds,
                  // Ticks on rows the search has hidden, or on another page, leave
                  // dataSource; without this, filtering would silently shrink the
                  // selection instead of narrowing only what is on screen.
                  preserveSelectedRowKeys: true,
                  onChange: (keys) => setPickedIds(keys as number[]),
                }}
              />
            )}
          </Space>
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
