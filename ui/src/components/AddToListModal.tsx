import { useEffect, useState } from 'react';
import { App, Alert, Checkbox, Input, Modal, Radio, Space, Spin, Statistic, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useSavedLists, useListMutations, useCurrentList, contactToEntry } from '../circulations/store';
import type { ContactResponse } from '../api/types';

/** A new list is chosen by picking this instead of an existing list id. */
const NEW_LIST = -1;

export interface AddToListSource {
  /** What is being added, for the dialog's title: "3 selected companies". */
  label: string;
  /**
   * Fetches the addresses to add. Called with the confirmed-only choice so flipping the
   * checkbox re-counts without the caller re-implementing the query.
   */
  collect: (confirmedOnly: boolean) => Promise<ContactResponse[]>;
  /** Distinguishes one preview query from another in the react-query cache. */
  cacheKey: unknown[];
}

/**
 * Adds a set of contacts to a circulation list.
 *
 * <p>The dialog previews before it writes: which addresses come back is decided by the
 * circ/main flags rather than by anything the user typed here, so showing the resulting
 * count first is the only way "add all matching" is an informed click. The preview query
 * is the same call the confirm makes, so what is counted is what is added.
 */
export default function AddToListModal({
  open,
  onClose,
  onAdded,
  source,
}: {
  open: boolean;
  onClose: () => void;
  /** Fires only after addresses were actually written — never on cancel. */
  onAdded?: () => void;
  source: AddToListSource | null;
}) {
  const { message } = App.useApp();
  const savedLists = useSavedLists();
  const current = useCurrentList();
  const { addTo, create } = useListMutations();

  const [target, setTarget] = useState<number | null>(null);
  const [newName, setNewName] = useState('');
  const [confirmedOnly, setConfirmedOnly] = useState(false);

  // Default to the current list every time the dialog opens: it is the common case, and a
  // remembered target from a previous session is exactly the wrong thing to write into.
  useEffect(() => {
    if (open) {
      setTarget(current.listId ?? null);
      setNewName('');
    }
  }, [open, current.listId]);

  const preview = useQuery({
    queryKey: ['add-to-list-preview', source?.cacheKey, confirmedOnly],
    queryFn: () => source!.collect(confirmedOnly),
    enabled: open && source != null,
  });

  const contacts = preview.data ?? [];
  const creatingNew = target === NEW_LIST;
  const canConfirm =
    contacts.length > 0 && (creatingNew ? newName.trim().length > 0 : target != null);

  const confirm = async () => {
    if (!canConfirm) return;
    const entries = contacts.map(contactToEntry);
    try {
      const listId = creatingNew ? (await create.mutateAsync({ name: newName.trim() })).id : target!;
      const result = await addTo.mutateAsync({ listId, entries });
      const name = creatingNew
        ? newName.trim()
        : (savedLists.data?.find((l) => l.id === listId)?.name ?? 'the current list');
      message.success(
        `Added ${result.added} address${result.added === 1 ? '' : 'es'} to ${name}` +
          (result.skipped ? ` (${result.skipped} already there)` : ''),
      );
      onAdded?.();
      onClose();
    } catch {
      /* the axios interceptor surfaces the error */
    }
  };

  // The number of distinct mailboxes is what matters, not the number of contact rows: two
  // people sharing a desk address is one message, and the list dedupes it away on write.
  const distinct = new Set(contacts.map((c) => c.contactValue.trim().toLowerCase())).size;

  return (
    <Modal
      open={open}
      onCancel={onClose}
      onOk={confirm}
      okText={contacts.length ? `Add ${distinct}` : 'Add'}
      okButtonProps={{ disabled: !canConfirm, loading: addTo.isPending || create.isPending }}
      title={source ? `Add ${source.label} to a circulation list` : 'Add to a circulation list'}
      width={520}
    >
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <div>
          <Typography.Text strong>Add to</Typography.Text>
          <Radio.Group
            style={{ display: 'block', marginTop: 8 }}
            value={target}
            onChange={(e) => setTarget(e.target.value)}
          >
            <Space direction="vertical" size={4}>
              <Radio value={current.listId ?? null} disabled={current.listId == null}>
                Current list{' '}
                <Typography.Text type="secondary">
                  ({current.entries.length} address{current.entries.length === 1 ? '' : 'es'})
                </Typography.Text>
              </Radio>
              {(savedLists.data ?? []).map((l) => (
                <Radio key={l.id} value={l.id}>
                  {l.name}{' '}
                  <Typography.Text type="secondary">
                    ({l.entryCount} address{l.entryCount === 1 ? '' : 'es'})
                  </Typography.Text>
                </Radio>
              ))}
              <Radio value={NEW_LIST}>New list…</Radio>
            </Space>
          </Radio.Group>
          {creatingNew && (
            <Input
              autoFocus
              style={{ marginTop: 8 }}
              placeholder="Name for the new list, e.g. Handysize owners — Med"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onPressEnter={confirm}
              maxLength={150}
            />
          )}
        </div>

        <Checkbox checked={confirmedOnly} onChange={(e) => setConfirmedOnly(e.target.checked)}>
          Confirmed contacts only
        </Checkbox>

        {preview.isLoading ? (
          <Spin />
        ) : contacts.length === 0 ? (
          <Alert
            type="info"
            showIcon
            message="No addresses to add"
            description={
              confirmedOnly
                ? 'Nothing here has a confirmed, working email address. Try again without the confirmed-only filter.'
                : 'Nothing here has a working email address on file.'
            }
          />
        ) : (
          <Space size="large">
            <Statistic title="Addresses" value={distinct} />
            <Statistic
              title="Sources"
              value={new Set(contacts.map((c) => c.personId ?? `c${c.companyId}`)).size}
            />
          </Space>
        )}

        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          Addresses are chosen by the contact flags: the ones flagged <b>circ</b> where a person
          has any, otherwise their <b>main</b> address, otherwise all their working addresses.
          Dead addresses are never collected.
        </Typography.Text>
      </Space>
    </Modal>
  );
}
