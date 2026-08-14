import { App, Button, Tooltip } from 'antd';
import { PlusOutlined, CheckCircleTwoTone } from '@ant-design/icons';
import { useCurrentList, contactToEntry } from '../circulations/store';
import type { ContactResponse } from '../api/types';

/**
 * Toggle an email contact in/out of the current circulation list. Renders nothing for
 * non-email contacts (phones), so it can be dropped into any contact row safely.
 */
export default function AddToListButton({ ct }: { ct: ContactResponse }) {
  const { has, entryFor, add, removeEntry, listId } = useCurrentList();
  const { message } = App.useApp();

  if (ct.contactKind !== 'email') return null;
  const inList = has(ct);
  const pending = add.isPending || removeEntry.isPending;

  // A dead address can still be removed from the list, just never added to it — the
  // campaign would drop it at send time anyway, so offering the add is only misleading.
  if (!ct.working && !inList) {
    return (
      <Tooltip title="Marked not working — excluded from circulations">
        <Button type="text" size="small" disabled aria-label="Not working" icon={<PlusOutlined />} />
      </Tooltip>
    );
  }

  return (
    <Tooltip title={inList ? 'In the current list — click to remove' : 'Add to the current list'}>
      <Button
        type="text"
        size="small"
        loading={pending}
        disabled={listId == null}
        aria-label={inList ? 'Remove from the current list' : 'Add to the current list'}
        icon={inList ? <CheckCircleTwoTone twoToneColor="#52c41a" /> : <PlusOutlined />}
        onClick={() => {
          if (inList) {
            const entry = entryFor(ct);
            if (!entry) return;
            removeEntry.mutate(entry.id, {
              onSuccess: () => message.info(`Removed ${ct.contactValue}`),
            });
          } else {
            add.mutate([contactToEntry(ct)], {
              onSuccess: () => message.success(`Added ${ct.contactValue}`),
            });
          }
        }}
      />
    </Tooltip>
  );
}
