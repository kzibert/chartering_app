import { useMemo } from 'react';
import { Select } from 'antd';
import { useTradeAreas } from '../api/hooks';

interface Props {
  value?: number;
  onChange?: (value?: number) => void;
  placeholder?: string;
  allowClear?: boolean;
  style?: React.CSSProperties;
}

/**
 * The trade-area picker.
 *
 * **Grouped by parent, not sorted flat.** Twenty-seven waters in one alphabetical list puts
 * the Adriatic three rows above the Baltic, which is a coincidence of spelling rather than
 * anything about the sea. Grouping under the Mediterranean and the Black Sea is how a
 * broker holds them, and it also makes visible the one thing the list has to teach: that
 * picking "Mediterranean" is a wider statement than picking "West Med".
 *
 * Searching matches the aliases as well as the name, because the word somebody has in mind
 * is the one their mail uses — typing "spain" has to find the West Med.
 */
export default function TradeAreaSelect({
  value,
  onChange,
  placeholder = 'Any area',
  allowClear = true,
  style,
}: Props) {
  const { data: areas } = useTradeAreas();

  const options = useMemo(() => {
    const all = areas ?? [];
    const tops = all.filter((a) => a.parentId == null);
    return tops.map((top) => {
      const children = all.filter((a) => a.parentId === top.id);
      // A top-level area with no children is still selectable in its own right, so it is
      // offered as the single member of its own group rather than dropped.
      return {
        label: top.name,
        options: [top, ...children].map((a) => ({
          value: a.id,
          label: a.parentId == null ? a.name : `${a.name} (${a.code})`,
          // What Select searches on. The aliases are the point — "spain med", "w.italy"
          // and "ecuk" are the words that reach for these.
          searchText: [a.name, a.code, ...(a.aliases ?? [])].join(' ').toLowerCase(),
        })),
      };
    });
  }, [areas]);

  return (
    <Select
      showSearch
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      allowClear={allowClear}
      style={style ?? { width: '100%' }}
      options={options}
      // Typed against the leaf option rather than the group: antd's own types describe a
      // grouped `options` prop as either shape, so `option` here is the union and reading a
      // custom field off it needs the narrowing to be explicit.
      filterOption={(input, option) => {
        const text = (option as { searchText?: string } | undefined)?.searchText;
        return text?.includes(input.toLowerCase()) ?? false;
      }}
    />
  );
}
