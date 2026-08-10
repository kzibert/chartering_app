import { Checkbox, Divider, Select, Space } from 'antd';

/**
 * A multi-select that renders a real checkbox against every option and pins a
 * "(none)" row at the top of the list to clear the whole selection in one click
 * (mirroring the explicit "Source: all" option rather than relying on the X).
 *
 * Designed to be dropped straight into a Form.Item, which injects value/onChange.
 */
interface Props {
  value?: string[];
  onChange?: (value: string[]) => void;
  options: string[];
  placeholder?: string;
}

export default function MultiCheckSelect({ value = [], onChange, options, placeholder }: Props) {
  const selected = new Set(value);

  return (
    <Select
      mode="multiple"
      allowClear
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      maxTagCount="responsive"
      optionLabelProp="label"
      // We draw our own checkbox on the left, so drop the default right-side tick.
      menuItemSelectedIcon={null}
      options={options.map((o) => ({ value: o, label: o }))}
      filterOption={(input, option) =>
        String(option?.label ?? '').toLowerCase().includes(input.toLowerCase())
      }
      optionRender={(opt) => (
        <Space size={8}>
          <Checkbox checked={selected.has(opt.value as string)} />
          {opt.label}
        </Space>
      )}
      dropdownRender={(menu) => (
        <>
          <div
            style={{ padding: '5px 12px', cursor: 'pointer', color: 'rgba(0,0,0,0.45)' }}
            // onMouseDown + preventDefault so the option list doesn't steal focus first.
            onMouseDown={(e) => {
              e.preventDefault();
              onChange?.([]);
            }}
          >
            (none — clear selection)
          </div>
          <Divider style={{ margin: 0 }} />
          {menu}
        </>
      )}
    />
  );
}
