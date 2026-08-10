import { useState } from 'react';
import { Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { companiesApi } from '../api/companies';

interface Props {
  value?: number;
  onChange?: (value?: number) => void;
  placeholder?: string;
  allowClear?: boolean;
}

/** Debounced company picker by name (shared by vessel/person/contact forms and filters). */
export default function CompanySelect({ value, onChange, placeholder, allowClear }: Props) {
  const [term, setTerm] = useState('');
  const { data, isFetching } = useQuery({
    queryKey: ['company-select', term],
    queryFn: () => companiesApi.search({ name: term || undefined, size: 20, sort: 'name,asc' }),
  });

  // The selected company is often outside the current search page (a restored filter,
  // or a record being edited). Without this the Select would render the bare id.
  const { data: selected } = useQuery({
    queryKey: ['company', value],
    queryFn: () => companiesApi.get(value!),
    enabled: value != null,
  });

  const label = (c: { name: string; cityName?: string }) =>
    c.cityName ? `${c.name} — ${c.cityName}` : c.name;

  const found = (data?.content ?? []).map((c) => ({ value: c.id, label: label(c) }));
  const options =
    value != null && selected && !found.some((o) => o.value === value)
      ? [{ value, label: label(selected.company) }, ...found]
      : found;

  return (
    <Select
      showSearch
      allowClear={allowClear}
      placeholder={placeholder ?? 'Search company…'}
      value={value}
      filterOption={false}
      onSearch={setTerm}
      onChange={(v) => onChange?.(v)}
      loading={isFetching}
      options={options}
      style={{ width: '100%' }}
    />
  );
}
