import { useState } from 'react';
import { Select, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { vesselsApi } from '../api/vessels';

interface Props {
  value?: number;
  onChange?: (value?: number) => void;
  placeholder?: string;
  allowClear?: boolean;
  disabled?: boolean;
}

/**
 * Debounced vessel picker, searching current and former names.
 *
 * The former names are the reason it exists rather than a plain lookup. A position list
 * arrives naming a ship this database holds under a name she was renamed out of years ago,
 * and someone typing what the list says has to land on the row that already exists — or the
 * same hull gets entered a second time and the fleet quietly doubles.
 *
 * Which is also why a match on a former name is labelled: the row that comes back is called
 * something else, and without the "ex" tag it looks like the wrong ship.
 */
export default function VesselSelect({
  value,
  onChange,
  placeholder = 'Search vessels…',
  allowClear,
  disabled,
}: Props) {
  const [term, setTerm] = useState('');
  const { data, isFetching } = useQuery({
    queryKey: ['vessel-select', term],
    queryFn: () =>
      vesselsApi.search({ name: term || undefined, size: 20, sort: 'name,asc', includeBanned: true }),
  });

  // The chosen vessel is usually outside the current search page — a record being edited, or
  // a restored filter — and without this the Select would render the bare id.
  const { data: selected } = useQuery({
    queryKey: ['vessel', value],
    queryFn: () => vesselsApi.get(value!),
    enabled: value != null,
  });

  const row = (v: {
    id: number;
    name: string;
    imoNumber?: string;
    exNames?: { name: string }[];
  }) => {
    // Only the former names that actually explain this hit are shown. Listing all of them
    // on every row is noise; the one somebody typed is the one worth pointing at.
    const hit = term
      ? (v.exNames ?? []).filter((e) => e.name.toLowerCase().includes(term.toLowerCase()))
      : [];
    return {
      value: v.id,
      title: v.name,
      label: (
        <span>
          {v.name}
          {v.imoNumber && <span style={{ opacity: 0.55 }}> · {v.imoNumber}</span>}
          {hit.length > 0 && (
            <Tag style={{ marginInlineStart: 6, marginInlineEnd: 0 }}>
              ex {hit.map((e) => e.name).join(', ')}
            </Tag>
          )}
        </span>
      ),
    };
  };

  const found = (data?.content ?? []).map(row);
  const options =
    value != null && selected?.vessel && !found.some((o) => o.value === value)
      ? [row(selected.vessel), ...found]
      : found;

  return (
    <Select
      showSearch
      value={value}
      onChange={onChange}
      onSearch={setTerm}
      // The server has already filtered on both name kinds; filtering again in the browser
      // would throw away every match that came back on a former name.
      filterOption={false}
      loading={isFetching}
      placeholder={placeholder}
      allowClear={allowClear}
      disabled={disabled}
      optionLabelProp="title"
      style={{ width: '100%' }}
      options={options}
    />
  );
}
