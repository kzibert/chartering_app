import { useState } from 'react';
import { Button, Space, Tag, Tooltip, Typography } from 'antd';
import { ArrowRightOutlined } from '@ant-design/icons';
import type { DataChangeResponse } from '../api/dataChanges';

/** Colours by what happened, so a column of entries is scannable without reading it. */
const OPERATION_COLOUR: Record<string, string> = {
  create: 'green',
  update: 'blue',
  delete: 'red',
};

/**
 * "What changed", as one line.
 *
 * Three shapes, because the log holds three. A field update reads as a sentence —
 * `working: true → false` — which is the whole reason updates are stored per field rather
 * than as a pair of snapshots. A create or a delete has only one side, and its values are a
 * JSON blob of the whole row, so it is summarised and opened on demand: nobody scanning a
 * history wants twelve fields of a newly created contact in their way, and the one time
 * they do want them is the time the record no longer exists to look at.
 */
export default function ChangeSummary({ change }: { change: DataChangeResponse }) {
  const [open, setOpen] = useState(false);

  if (change.fieldName) {
    return (
      <Space size={4} wrap>
        <Typography.Text strong style={{ fontSize: 13 }}>
          {change.fieldName}
        </Typography.Text>
        <Value text={change.oldValue} muted />
        <ArrowRightOutlined style={{ fontSize: 11, opacity: 0.45 }} />
        <Value text={change.newValue} />
      </Space>
    );
  }

  const snapshot = change.operation === 'delete' ? change.oldValue : change.newValue;
  const fields = parseSnapshot(snapshot);

  return (
    <Space direction="vertical" size={2} style={{ width: '100%' }}>
      <Space size={4} wrap>
        <Typography.Text style={{ fontSize: 13 }}>
          {change.operation === 'delete' ? 'Record deleted' : 'Record created'}
        </Typography.Text>
        {fields.length > 0 && (
          <Button type="link" size="small" style={{ padding: 0 }} onClick={() => setOpen(!open)}>
            {open ? 'hide' : `${fields.length} field${fields.length === 1 ? '' : 's'}`}
          </Button>
        )}
      </Space>
      {open && (
        <div
          style={{
            fontSize: 12,
            // The snapshot of a deleted record is the only copy of it left, so it gets room
            // to be read rather than being truncated into a tooltip.
            maxHeight: 220,
            overflowY: 'auto',
            background: 'rgba(0,0,0,0.02)',
            borderRadius: 4,
            padding: '6px 8px',
          }}
        >
          {fields.map(([key, value]) => (
            <div key={key} style={{ display: 'flex', gap: 8 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12, minWidth: 120 }}>
                {key}
              </Typography.Text>
              <Typography.Text style={{ fontSize: 12, wordBreak: 'break-all' }}>
                {value}
              </Typography.Text>
            </div>
          ))}
        </div>
      )}
    </Space>
  );
}

/** The record an entry is about, named as it was at the time. */
export function ChangeTarget({ change }: { change: DataChangeResponse }) {
  return (
    <Space size={4} wrap>
      <Tag color={OPERATION_COLOUR[change.operation]} style={{ marginInlineEnd: 0 }}>
        {change.operation}
      </Tag>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {change.entityType}
      </Typography.Text>
      <Typography.Text style={{ fontSize: 13 }}>
        {change.entityLabel ?? `#${change.entityId}`}
      </Typography.Text>
    </Space>
  );
}

/**
 * One value as it appears either side of the arrow.
 *
 * An empty value is rendered as the word rather than as nothing at all: a blank cell beside
 * an arrow reads as a rendering bug, and "was it cleared or did the log fail" is exactly
 * the question the log exists to answer.
 */
function Value({ text, muted }: { text?: string; muted?: boolean }) {
  if (text == null) {
    return (
      <Typography.Text type="secondary" italic style={{ fontSize: 13 }}>
        empty
      </Typography.Text>
    );
  }
  const long = text.length > 60;
  const shown = long ? `${text.slice(0, 60)}…` : text;
  const body = (
    <Typography.Text
      type={muted ? 'secondary' : undefined}
      delete={muted}
      style={{ fontSize: 13, wordBreak: 'break-word' }}
    >
      {shown}
    </Typography.Text>
  );
  return long ? <Tooltip title={text}>{body}</Tooltip> : body;
}

/**
 * The JSON snapshot as pairs, or nothing if it will not parse.
 *
 * Failing quietly is deliberate. A snapshot that cannot be read is a curiosity in a log
 * entry; throwing here would take down the whole history screen, including the entries that
 * are fine — which is the moment somebody most needs to look at them.
 */
function parseSnapshot(json?: string): [string, string][] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json) as Record<string, string>;
    return Object.entries(parsed);
  } catch {
    return [];
  }
}
