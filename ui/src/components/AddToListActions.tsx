import { useState } from 'react';
import { Button, Space, Tooltip } from 'antd';
import { MailOutlined, CloseCircleOutlined } from '@ant-design/icons';
import AddToListModal, { type AddToListSource } from './AddToListModal';
import type { ContactResponse } from '../api/types';

/**
 * The "add these to a circulation list" pair of buttons shared by the Companies, Vessels
 * and People tabs: one for the ticked rows, one for everything matching the current
 * filters. Both open the same dialog, which previews the addresses before writing.
 *
 * <p>Add-all deliberately covers the whole filtered set rather than the visible page —
 * the page size is an artefact of the table, not a statement about who to circulate to.
 */
export default function AddToListActions({
  entity,
  selectedIds,
  totalMatching,
  collect,
  onCleared,
}: {
  /** Plural noun for the dialog and tooltips, e.g. "companies". */
  entity: string;
  selectedIds: number[];
  /** Result count of the current filter, shown on the add-all button. */
  totalMatching: number;
  /**
   * Fetches the addresses to add. `ids` is empty for the add-all case, which the API reads
   * as "use the filter instead" — so the caller passes its filter in once, at binding time.
   */
  collect: (ids: number[], confirmedOnly: boolean) => Promise<ContactResponse[]>;
  /**
   * Drops the caller's tick marks. Called after a successful add, and by the Clear
   * selection button. Deliberately *not* called on cancel — losing a selection you spent
   * time building because you backed out of the dialog is its own small disaster.
   */
  onCleared?: () => void;
}) {
  const [source, setSource] = useState<AddToListSource | null>(null);

  const open = (ids: number[], label: string) =>
    setSource({
      label,
      cacheKey: [entity, ids],
      collect: (confirmedOnly) => collect(ids, confirmedOnly),
    });

  const n = selectedIds.length;

  return (
    <>
      <Space wrap>
        <Tooltip
          title={
            n
              ? `Add the addresses of the ${n} ticked ${entity} to a circulation list`
              : `Tick some rows first, or use "Add all matching"`
          }
        >
          <Button icon={<MailOutlined />} disabled={n === 0} onClick={() => open(selectedIds, `${n} selected ${entity}`)}>
            Add {n || ''} selected
          </Button>
        </Tooltip>
        <Tooltip title={`Add the addresses of every one of the ${entity} matching the current filters — not just this page`}>
          <Button
            icon={<MailOutlined />}
            disabled={totalMatching === 0}
            onClick={() => open([], `all ${totalMatching} matching ${entity}`)}
          >
            Add all {totalMatching || ''} matching
          </Button>
        </Tooltip>
        {/* Only rendered while something is ticked: a permanently visible "clear" that is
            disabled most of the time is noise, and the selection can span pages, so there
            is no other single place to undo it from. */}
        {n > 0 && (
          <Tooltip title={`Untick all ${n} selected ${entity}, including any on other pages`}>
            <Button type="text" icon={<CloseCircleOutlined />} onClick={onCleared}>
              Clear selection
            </Button>
          </Tooltip>
        )}
      </Space>
      <AddToListModal
        open={source != null}
        onClose={() => setSource(null)}
        onAdded={onCleared}
        source={source}
      />
    </>
  );
}
