import { useState, type CSSProperties, type Key, type MouseEventHandler, type ReactNode } from 'react';
import { Button, Checkbox, Empty, Pagination, Select, Spin, Table, Typography, theme } from 'antd';
import { DownOutlined, UpOutlined } from '@ant-design/icons';
import type { TableProps } from 'antd';
import type { RowSelectMethod, SorterResult } from 'antd/es/table/interface';
import { useIsMobile } from '../responsive/useIsMobile';

export interface MobileField {
  label: string;
  value: ReactNode;
}

/** How one row of the table reads once it is a card instead. */
export interface MobileCard<T> {
  /** The line you scan for — the name, the subject, the address. */
  title: (row: T) => ReactNode;
  /** One line under it for context: the company, the sender, the owner. */
  subtitle?: (row: T) => ReactNode;
  /** Label/value pairs, two to a row. Falsy entries are dropped, so a field can be made
      conditional inline (`x && { … }`) instead of building the array in two steps — which
      is why the empty string and 0 are in the type: `'' && {…}` is `''`, not `false`. */
  fields?: (row: T) => (MobileField | false | null | undefined | '' | 0)[];
  /** Buttons at the foot of the card. Rendered outside the tap target, so pressing one
      does not also open whatever a row click opens. */
  actions?: (row: T) => ReactNode;
  /** Label on the expander, when the desktop table has an expandable row. */
  expandLabel?: (row: T) => ReactNode;
}

/** A column the phone layout offers to sort by, since there are no headers to click. */
export interface MobileSortOption {
  field: string;
  label: string;
}

type Props<T> = TableProps<T> & {
  mobile: MobileCard<T>;
  mobileSort?: MobileSortOption[];
};

/**
 * A table on a desktop, a list of cards on a phone.
 *
 * The point of doing it this way — one component taking the same props antd's Table takes
 * — is that a page keeps exactly one description of its data. A second, parallel mobile
 * page would be a second copy of every column, every sorter and every click handler, and
 * the two would drift apart on the first change that only got made to one of them. Here
 * the cost of a page working on a phone is the `mobile` prop, and a column added to
 * `columns` is a column both layouts have.
 *
 * A horizontally-scrolling table is the other obvious answer, and is rejected on purpose:
 * the vessel table is thirteen columns, which on a 390px screen is legible two at a time,
 * and reading one row means swiping back and forth without ever seeing the vessel's name
 * and the figure you are checking at the same moment.
 */
export default function ResponsiveTable<T extends object>({
  mobile,
  mobileSort,
  ...table
}: Props<T>) {
  const isMobile = useIsMobile();
  // Untouched pass-through: the desktop layout is what the rest of the app was written
  // against, and anything this component knows nothing about still has to reach the Table.
  if (!isMobile) return <Table<T> {...table} />;
  return <MobileCardList table={table} mobile={mobile} sortOptions={mobileSort} />;
}

function MobileCardList<T extends object>({
  table,
  mobile,
  sortOptions,
}: {
  table: TableProps<T>;
  mobile: MobileCard<T>;
  sortOptions?: MobileSortOption[];
}) {
  const { token } = theme.useToken();
  const rows = (table.dataSource ?? []) as readonly T[];
  const selection = table.rowSelection;
  const expandable = table.expandable;
  const loading = typeof table.loading === 'boolean' ? table.loading : Boolean(table.loading);

  const pagination =
    table.pagination === false || table.pagination == null ? undefined : table.pagination;
  // A pagination config with no `total` is antd's client-side mode: the Table holds every
  // row and slices them itself. There is no server page to ask for in that case, so the
  // slicing has to happen here instead.
  const clientSide = pagination != null && pagination.total == null;
  const [clientPage, setClientPage] = useState(1);
  const pageSize = pagination?.pageSize ?? 20;
  const total = pagination?.total ?? rows.length;
  // Clamped, because in client-side mode the page number outlives the rows: narrowing a
  // search from 120 matches to 8 while sitting on page 3 would otherwise show an empty
  // list rather than the eight results the search just found.
  const lastPage = Math.max(1, Math.ceil(total / pageSize));
  const current = clientSide ? Math.min(clientPage, lastPage) : (pagination?.current ?? 1);
  const visible = clientSide ? rows.slice((current - 1) * pageSize, current * pageSize) : rows;

  const keyOf = (row: T, index: number): Key => {
    const rk = table.rowKey ?? 'key';
    if (typeof rk === 'function') return rk(row, index);
    return ((row as Record<string, unknown>)[rk as string] as Key) ?? index;
  };

  /*
   * antd's Table keeps the active sort in its own header state. There are no headers here,
   * so it is read back off the columns the page handed in — and it has to be sent again
   * with every page change, because useTableControls rebuilds its `sort` from the sorter
   * argument and reads an empty one as "sorting was cleared". Without this, turning the
   * page would quietly unsort the results.
   */
  const sorted = (table.columns ?? []).find((c) => (c as { sortOrder?: string }).sortOrder) as
    | { sortOrder?: 'ascend' | 'descend'; dataIndex?: unknown; key?: Key }
    | undefined;
  const sortedField = sorted ? String(sorted.dataIndex ?? sorted.key ?? '') : '';
  const activeSorter = (sorted ? { field: sortedField, order: sorted.sortOrder } : {}) as SorterResult<T>;

  const emit = (page: number, size: number, sorter: SorterResult<T>, action: 'paginate' | 'sort') =>
    table.onChange?.({ current: page, pageSize: size }, {}, sorter, {
      currentDataSource: rows as T[],
      action,
    });

  const goToPage = (page: number, size: number) => {
    if (clientSide) setClientPage(page);
    else emit(page, size, activeSorter, 'paginate');
  };

  const sortItems = (sortOptions ?? []).flatMap((o) => [
    { value: `${o.field}:ascend`, label: `${o.label} ↑` },
    { value: `${o.field}:descend`, label: `${o.label} ↓` },
  ]);

  // Selected keys can include rows from other pages — every table here sets
  // preserveSelectedRowKeys — so a tick only ever adds or removes keys that are on screen
  // and leaves the rest of the selection alone.
  const selectedKeys = (selection?.selectedRowKeys ?? []) as Key[];
  const isPicked = (key: Key) => selectedKeys.includes(key);
  const setSelection = (keys: Key[], type: RowSelectMethod) =>
    selection?.onChange?.(keys, rows.filter((r, i) => keys.includes(keyOf(r, i))) as T[], { type });
  const toggleRow = (key: Key, checked: boolean) =>
    setSelection(
      checked ? [...selectedKeys, key] : selectedKeys.filter((k) => k !== key),
      'single',
    );
  const visibleKeys = visible.map((r, i) => keyOf(r, i));
  const pickedHere = visibleKeys.filter(isPicked).length;
  const toggleAll = (checked: boolean) =>
    setSelection(
      checked
        ? [...selectedKeys, ...visibleKeys.filter((k) => !isPicked(k))]
        : selectedKeys.filter((k) => !visibleKeys.includes(k)),
      checked ? 'all' : 'none',
    );

  // Expansion follows the page when the page controls it (People expands every row while a
  // contact search is running), and is local otherwise.
  const [ownExpanded, setOwnExpanded] = useState<Key[]>([]);
  const expandedKeys = (expandable?.expandedRowKeys as Key[] | undefined) ?? ownExpanded;
  const setExpanded = (keys: Key[]) => {
    if (expandable?.onExpandedRowsChange) expandable.onExpandedRowsChange(keys);
    else setOwnExpanded(keys);
  };

  const cardStyle: CSSProperties = {
    border: `1px solid ${token.colorBorderSecondary}`,
    borderRadius: token.borderRadiusLG,
    background: token.colorBgContainer,
    padding: 12,
    display: 'flex',
    gap: 10,
  };

  return (
    <Spin spinning={loading}>
      {(selection || sortItems.length > 0) && (
        <div
          style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap', marginBottom: 8 }}
        >
          {selection && (
            <Checkbox
              indeterminate={pickedHere > 0 && pickedHere < visible.length}
              checked={visible.length > 0 && pickedHere === visible.length}
              onChange={(e) => toggleAll(e.target.checked)}
            >
              {selectedKeys.length > 0 ? `${selectedKeys.length} selected` : 'Select page'}
            </Checkbox>
          )}
          {selection && selectedKeys.length > 0 && (
            <Button type="link" size="small" onClick={() => setSelection([], 'none')}>
              Clear
            </Button>
          )}
          <span style={{ flex: 1 }} />
          {sortItems.length > 0 && (
            <Select
              size="small"
              style={{ minWidth: 150 }}
              placeholder="Sort by…"
              allowClear
              value={sorted ? `${sortedField}:${sorted.sortOrder}` : undefined}
              options={sortItems}
              onChange={(v?: string) => {
                const [field, order] = (v ?? '').split(':');
                // Back to page one: a re-sort that kept you on page 4 shows the fourth
                // page of a different ordering, which reads as the results having vanished.
                emit(1, pageSize, (field ? { field, order } : {}) as SorterResult<T>, 'sort');
              }}
            />
          )}
        </div>
      )}

      {visible.length === 0 && !loading ? (
        // The page's own empty text where there is one: on the Mailbox it is what explains
        // that nothing has been synced because nothing has been configured.
        (table.locale?.emptyText as ReactNode) ?? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} />
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {visible.map((row, index) => {
            const key = keyOf(row, index);
            const rowProps = table.onRow?.(row, index) ?? {};
            const canExpand =
              expandable?.expandedRowRender != null && (expandable.rowExpandable?.(row) ?? true);
            const open = expandedKeys.includes(key);
            const fields = (mobile.fields?.(row) ?? []).filter(Boolean) as MobileField[];
            const actions = mobile.actions?.(row);

            return (
              <div key={String(key)} style={cardStyle}>
                {selection && (
                  <Checkbox
                    checked={isPicked(key)}
                    onChange={(e) => toggleRow(key, e.target.checked)}
                    // alignSelf, or the flex row stretches the checkbox to the card's full
                    // height and antd centres the box inside it — landing it beside a field
                    // halfway down, where it reads as belonging to that field.
                    style={{ alignSelf: 'flex-start', marginTop: 2 }}
                  />
                )}
                <div style={{ flex: 1, minWidth: 0 }}>
                  {/* Only this block carries the row's click handler. On the whole card it
                      would mean every tap on an action button also opened the drawer. */}
                  <div
                    onClick={rowProps.onClick as MouseEventHandler}
                    style={{ minWidth: 0, cursor: rowProps.onClick ? 'pointer' : undefined }}
                  >
                    <div style={{ fontWeight: 600, wordBreak: 'break-word' }}>{mobile.title(row)}</div>
                    {mobile.subtitle && (
                      <div style={{ color: token.colorTextSecondary, fontSize: 13, marginTop: 2 }}>
                        {mobile.subtitle(row)}
                      </div>
                    )}
                    {fields.length > 0 && (
                      <div
                        style={{
                          display: 'grid',
                          gridTemplateColumns: 'repeat(2, minmax(0, 1fr))',
                          gap: '6px 12px',
                          marginTop: 8,
                        }}
                      >
                        {fields.map((f) => (
                          <div key={f.label} style={{ minWidth: 0 }}>
                            <div
                              style={{
                                fontSize: 11,
                                color: token.colorTextTertiary,
                                textTransform: 'uppercase',
                                letterSpacing: 0.3,
                              }}
                            >
                              {f.label}
                            </div>
                            <div style={{ fontSize: 13, wordBreak: 'break-word' }}>{f.value}</div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>

                  {actions && <div style={{ marginTop: 10 }}>{actions}</div>}

                  {canExpand && (
                    <>
                      <Button
                        type="link"
                        size="small"
                        style={{ paddingInline: 0, marginTop: 4 }}
                        icon={open ? <UpOutlined /> : <DownOutlined />}
                        onClick={() =>
                          setExpanded(
                            open ? expandedKeys.filter((k) => k !== key) : [...expandedKeys, key],
                          )
                        }
                      >
                        {mobile.expandLabel?.(row) ?? 'Details'}
                      </Button>
                      {open && (
                        <div style={{ marginTop: 4 }}>
                          {expandable!.expandedRowRender!(row, index, 0, true)}
                        </div>
                      )}
                    </>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {pagination && total > pageSize && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 8,
            marginTop: 12,
          }}
        >
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {total} total
          </Typography.Text>
          <Pagination simple size="small" current={current} pageSize={pageSize} total={total} onChange={goToPage} />
        </div>
      )}
    </Spin>
  );
}
