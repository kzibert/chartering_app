import { useState, type ReactNode } from 'react';
import { Badge, Button, Card, Drawer, Space } from 'antd';
import { FilterOutlined, SearchOutlined } from '@ant-design/icons';
import type { FormInstance } from 'antd';
import { useIsMobile } from '../responsive/useIsMobile';

interface Props {
  /** The page's form. Needed because on a phone the Search button is no longer inside the
      `<form>` element and cannot submit it natively — see below. */
  form: FormInstance;
  /** The filter fields. On a phone these move into a drawer. */
  children: ReactNode;
  /** Buttons that are not filters — "New vessel", the add-to-list menu. Always on screen. */
  actions?: ReactNode;
  /** Secondary filter controls (the source dropdown, "include banned"). Beside the buttons
      on a desktop; with the rest of the fields in the drawer on a phone. */
  extras?: ReactNode;
  /** Clears the form and re-runs the search. */
  onReset: () => void;
  /** How many filters are set, for the badge on the Filters button. */
  activeCount?: number;
}

/**
 * The filter card at the top of every list page.
 *
 * On a desktop this is exactly what those pages had: a small Card holding the fields and a
 * row of buttons under them. On a phone the fields move into a drawer behind a Filters
 * button, because they do not fit — the vessel search alone is fourteen boxes, which is
 * three screens of form standing between the user and the first result they came to see.
 * The badge on the button is what keeps that honest: filters you cannot see are filters
 * you forget are on, and "no results" then looks like a broken search.
 *
 * Search and Reset live here rather than in each page because their position differs
 * between the two layouts — a button row on a desktop, a pinned drawer footer on a phone —
 * and every page spelled them the same way anyway.
 */
export default function FilterPanel({
  form,
  children,
  actions,
  extras,
  onReset,
  activeCount = 0,
}: Props) {
  const isMobile = useIsMobile();
  const [open, setOpen] = useState(false);

  if (!isMobile) {
    return (
      <Card size="small" style={{ marginBottom: 16 }}>
        {children}
        <Space wrap>
          <Button type="primary" htmlType="submit" icon={<SearchOutlined />}>
            Search
          </Button>
          <Button onClick={onReset}>Reset</Button>
          {actions}
          {extras}
        </Space>
      </Card>
    );
  }

  const search = () => {
    // form.submit() rather than htmlType="submit": the drawer renders into a portal at the
    // end of <body>, so this button is outside the page's <form> element and a native
    // submit never reaches it. The fields inside the drawer are unaffected — rc-field-form
    // registers them through React context, which crosses the portal fine.
    form.submit();
    setOpen(false);
  };

  return (
    <Card size="small" style={{ marginBottom: 12 }}>
      <Space wrap>
        <Badge count={activeCount} size="small">
          <Button icon={<FilterOutlined />} onClick={() => setOpen(true)}>
            Filters
          </Button>
        </Badge>
        {actions}
      </Space>

      <Drawer
        open={open}
        onClose={() => setOpen(false)}
        placement="bottom"
        height="85%"
        title="Filters"
        // The fields have to exist whether or not the drawer has ever been opened: they
        // hold the persisted search, and an unmounted Form.Item is a field the form does
        // not know about, so submitting from the button row would drop it.
        forceRender
        footer={
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Button
              onClick={() => {
                onReset();
                setOpen(false);
              }}
            >
              Reset
            </Button>
            <Button type="primary" icon={<SearchOutlined />} onClick={search}>
              Search
            </Button>
          </Space>
        }
      >
        {children}
        {extras && <Space wrap>{extras}</Space>}
      </Drawer>
    </Card>
  );
}

/**
 * How many filters are actually set, for the badge.
 *
 * Blank strings, unticked checkboxes and empty multi-selects all count as "not filtering"
 * — they are what the form holds when nothing has been typed, and counting them would put
 * a permanent badge on the button that means nothing.
 */
export function countActiveFilters(filters: Record<string, unknown>): number {
  return Object.values(filters).filter((v) => {
    if (v == null || v === '' || v === false) return false;
    if (Array.isArray(v)) return v.length > 0;
    return true;
  }).length;
}
