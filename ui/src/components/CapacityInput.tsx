import { InputNumber, Tooltip } from 'antd';
import { toCbft, toM3, unitSign } from '../vessels/capacity';
import type { CapacityUnit } from '../vessels/capacity';

interface Props {
  /** Always cubic metres — what the form holds and the API filters on. */
  value?: number | null;
  onChange?: (m3: number | null) => void;
  /** The unit the figure is shown and typed in. Held by the page, so one switch sets every box. */
  unit: CapacityUnit;
  /** Clicking the sign after the number asks for the other unit. */
  onUnitChange?: (unit: CapacityUnit) => void;
  placeholder?: string;
}

/**
 * A hold capacity box that can be typed in either unit.
 *
 * The form's value never leaves cubic metres, whatever the box shows: the unit only changes how
 * the same figure is displayed and typed. Switching converts what is in the box — 150,000 cbft
 * becomes 4,248 m³ and back — so a figure copied out of a circular in cubic feet is searched for
 * as it was written, without doing the division by hand and without the filter ever being sent a
 * number in the wrong unit.
 *
 * Unrounded underneath, rounded on screen: a figure typed in cubic feet shows back exactly as
 * typed, where rounding the stored cubic metres would nudge it by a few feet on every keystroke.
 */
export default function CapacityInput({ value, onChange, unit, onUnitChange, placeholder }: Props) {
  const shown = value == null ? null : Math.round((unit === 'm3' ? value : toCbft(value)) * 100) / 100;
  const other: CapacityUnit = unit === 'm3' ? 'cbft' : 'm3';
  const toggle = () => onUnitChange?.(other);

  return (
    <InputNumber
      style={{ width: '100%' }}
      min={0}
      value={shown}
      placeholder={placeholder}
      onChange={(v) => {
        if (v == null) return onChange?.(null);
        const n = Number(v);
        onChange?.(unit === 'm3' ? n : toM3(n));
      }}
      addonAfter={
        <Tooltip title={`Typed in ${unitSign(unit)} — click to switch every capacity box to ${unitSign(other)}. The search always runs in m³.`}>
          <span
            role="button"
            tabIndex={0}
            aria-label={`Switch to ${unitSign(other)}`}
            onClick={toggle}
            onKeyDown={(e) => (e.key === 'Enter' || e.key === ' ') && toggle()}
            style={{ cursor: 'pointer', userSelect: 'none', display: 'inline-block', minWidth: 28, textAlign: 'center' }}
          >
            {unitSign(unit)}
          </span>
        </Tooltip>
      }
    />
  );
}
