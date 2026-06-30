import { Select } from 'antd';
import { usePeople } from '../api/hooks';

interface Props {
  value?: number;
  onChange?: (value?: number) => void;
  companyId?: number; // narrows the list when a company is chosen
  allowClear?: boolean;
}

/** Person picker, optionally scoped to a company (uses GET /people?companyId). */
export default function PersonSelect({ value, onChange, companyId, allowClear }: Props) {
  const { data, isFetching } = usePeople(companyId);
  const options = (data ?? []).map((p) => ({
    value: p.id,
    label: p.companyName ? `${p.fullName} — ${p.companyName}` : p.fullName,
  }));

  return (
    <Select
      showSearch
      allowClear={allowClear}
      placeholder="Search person…"
      value={value}
      optionFilterProp="label"
      onChange={(v) => onChange?.(v)}
      loading={isFetching}
      options={options}
      style={{ width: '100%' }}
    />
  );
}
