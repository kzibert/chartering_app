import { useMemo } from 'react';
import { Select, Tag } from 'antd';
import { usePorts } from '../api/hooks';

interface Props {
  value?: number;
  onChange?: (value?: number) => void;
  placeholder?: string;
  allowClear?: boolean;
  style?: React.CSSProperties;
}

/**
 * The port picker, showing each port's trade area beside it.
 *
 * The area tag is the reason this is not a plain Select over the lookup. Picking a load port
 * is what decides, silently, which vessels Match will consider — a Salerno cargo is a West
 * Med cargo — and the tag puts that consequence on screen at the moment of the choice
 * rather than after saving.
 *
 * Ports with no area yet show a muted "no area" instead of nothing. Around a dozen are in
 * that state, and a blank there reads as a rendering slip; naming it makes it a small piece
 * of data somebody can go and fix.
 */
export default function PortSelect({
  value,
  onChange,
  placeholder = 'Search ports',
  allowClear = true,
  style,
}: Props) {
  const { data: ports } = usePorts();

  const options = useMemo(
    () =>
      (ports ?? []).map((p) => ({
        value: p.id,
        // The plain name is what the closed Select shows; the tag lives in the dropdown row
        // only, where there is room for it.
        label: (
          <span>
            {p.name}{' '}
            {p.tradeAreaCode ? (
              <Tag color="blue" style={{ marginInlineEnd: 0 }}>{p.tradeAreaCode}</Tag>
            ) : (
              <Tag style={{ marginInlineEnd: 0 }}>no area</Tag>
            )}
          </span>
        ),
        searchText: `${p.name} ${p.tradeAreaCode ?? ''}`.toLowerCase(),
        title: p.name,
      })),
    [ports],
  );

  return (
    <Select
      showSearch
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      allowClear={allowClear}
      style={style ?? { width: '100%' }}
      options={options}
      optionLabelProp="title"
      filterOption={(input, option) =>
        (option?.searchText as string | undefined)?.includes(input.toLowerCase()) ?? false
      }
    />
  );
}
