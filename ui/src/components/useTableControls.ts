import type { TablePaginationConfig } from 'antd';
import { usePersistedState } from './usePersistedState';

export interface TableState {
  page: number; // 0-based for the API
  size: number;
  sort?: string; // "field,dir" for Spring Data
}

/**
 * Bridges antd Table pagination/sort to the backend's page/size/sort params.
 * Pass `persistKey` to keep the page/size/sort across tab switches, so returning
 * to a search lands on the same page of results you left.
 */
export function useTableControls(initial?: Partial<TableState>, persistKey?: string) {
  const [state, setState] = usePersistedState<TableState>(
    persistKey && `${persistKey}.table`,
    { page: 0, size: 20, ...initial },
  );

  // Loosely typed (any) so the same handler binds to Table<T> for any record type T.
  function onChange(
    pagination: TablePaginationConfig,
    _filters: Record<string, unknown>,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    sorter: any,
  ) {
    const s = Array.isArray(sorter) ? sorter[0] : sorter;
    const sort =
      s && s.field && s.order
        ? `${String(s.field)},${s.order === 'ascend' ? 'asc' : 'desc'}`
        : undefined;
    setState({
      page: (pagination.current ?? 1) - 1,
      size: pagination.pageSize ?? 20,
      sort,
    });
  }

  function pagination(totalElements: number): TablePaginationConfig {
    return {
      current: state.page + 1,
      pageSize: state.size,
      total: totalElements,
      showSizeChanger: true,
      showTotal: (t) => `${t} total`,
    };
  }

  /** Reset to first page (call when filters change). */
  function resetPage() {
    setState((p) => ({ ...p, page: 0 }));
  }

  /**
   * antd `sortOrder` for a sortable column. A restored sort drives the query, but the
   * Table's own header state starts empty on remount — without this the arrow would
   * disappear while the results stayed sorted.
   */
  function sortOrderFor(field: string): 'ascend' | 'descend' | undefined {
    if (!state.sort) return undefined;
    const [sorted, dir] = state.sort.split(',');
    if (sorted !== field) return undefined;
    return dir === 'desc' ? 'descend' : 'ascend';
  }

  return { state, onChange, pagination, resetPage, sortOrderFor };
}
