import { useMemo, useState } from 'react';
import { Button, Descriptions, Space, Tag, Tooltip, Typography } from 'antd';
import { EditOutlined, PlusOutlined } from '@ant-design/icons';
import { usePosition } from '../../api/hooks';
import PositionForm from '../openFleet/PositionForm';
import { POSITION_STATUS_META, formatOpenDates, staleness } from '../openFleet/status';
import type { VesselLastPositionResponse } from '../../api/types';

interface Props {
  vesselId: number;
  vesselName: string;
  lastPosition?: VesselLastPositionResponse;
}

/**
 * Where this ship was last reported free, on her own record — and the two ways to change it.
 *
 * **Two actions, not one, and the difference is the point.** Positions are append-only: a
 * reading is a fact with a date on it, and the whole Open Fleet design rests on never
 * overwriting one. So "a newer list arrived" and "I typed that wrong" cannot be the same
 * button:
 *
 * - **Update** records a *new* reading, which supersedes the same reporter's previous one
 *   and leaves it in her history. This is the common case and is the primary action.
 * - **Correct** edits the reading itself, for a typo. It rewrites history, so it is the
 *   quiet one, and it is only offered when there is something to correct.
 *
 * A button labelled "Edit" doing the first would be a lie about what the record keeps; one
 * doing the second for a fresh list would silently destroy the ship's history a week at a
 * time. The copy under the buttons says which is which, because nobody reads a tooltip
 * before their first click.
 */
export default function VesselLastOpen({ vesselId, vesselName, lastPosition }: Props) {
  const [creating, setCreating] = useState(false);
  const [correctingId, setCorrectingId] = useState<number>();

  // The form edits a full position; the record only carries the slim shape, so the whole
  // row is fetched on demand. Only when Correct is pressed — the common path never pays it.
  const correcting = usePosition(correctingId);

  // Referentially stable, or PositionForm's prefill effect would re-run on every render.
  const defaults = useMemo(() => ({ vesselId }), [vesselId]);

  const status = lastPosition ? POSITION_STATUS_META[lastPosition.status] : undefined;
  const age = lastPosition ? staleness(lastPosition.ageDays) : undefined;

  return (
    <>
      <Typography.Title level={5} style={{ marginTop: 20 }}>
        Last open
      </Typography.Title>

      {lastPosition ? (
        <Descriptions column={1} size="small" bordered>
          <Descriptions.Item label="Where">
            <Space size={6} wrap>
              {lastPosition.openPortName ?? lastPosition.openPortText ?? lastPosition.openAreaName ?? '—'}
              {lastPosition.openAreaCode && <Tag color="blue">{lastPosition.openAreaCode}</Tag>}
              {/* Only when it is not live. A green "Live" tag on every vessel record is
                  noise; "Fixed" or "Withdrawn" on this one is the whole story. */}
              {lastPosition.status !== 'LIVE' && (
                <Tooltip title={status?.hint}>
                  <Tag color={status?.color}>{status?.label}</Tag>
                </Tooltip>
              )}
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="Dates">
            {formatOpenDates(lastPosition.openFrom, lastPosition.openTo, lastPosition.openText)}
          </Descriptions.Item>
          {lastPosition.lastCargo && (
            <Descriptions.Item label="Last cargo">{lastPosition.lastCargo}</Descriptions.Item>
          )}
          {lastPosition.cargoPreferences && (
            <Descriptions.Item label="Prefers">{lastPosition.cargoPreferences}</Descriptions.Item>
          )}
          <Descriptions.Item label="Reported">
            <Space size={6} wrap>
              {/* Staleness is coloured the same way the fleet list colours it — a reading a
                  fortnight old is an archive entry, not a position. */}
              <Typography.Text
                type={age?.color === 'red' ? 'danger' : undefined}
                style={{ color: age?.color === 'orange' ? '#d46b08' : undefined }}
              >
                {age?.text}
              </Typography.Text>
              {lastPosition.reportedByCompanyName && (
                <Typography.Text type="secondary">by {lastPosition.reportedByCompanyName}</Typography.Text>
              )}
            </Space>
          </Descriptions.Item>
        </Descriptions>
      ) : (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          No position has ever been reported for {vesselName}. She will not appear on the Open
          fleet tab or in Match until one is.
        </Typography.Paragraph>
      )}

      <Space wrap style={{ marginTop: 8 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreating(true)}>
          {lastPosition ? 'Update position' : 'Record position'}
        </Button>
        {lastPosition && (
          <Button
            icon={<EditOutlined />}
            loading={correctingId != null && correcting.isLoading}
            onClick={() => setCorrectingId(lastPosition.id)}
          >
            Correct this reading
          </Button>
        )}
      </Space>
      {lastPosition && (
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 6, marginBottom: 0 }}>
          Update keeps this reading in her history and adds a new one. Correct rewrites this
          one — for a typo, not for a fresh list.
        </Typography.Paragraph>
      )}

      <PositionForm
        open={creating}
        defaults={defaults}
        lockVessel
        onClose={() => setCreating(false)}
      />
      {/* Held closed until the full row has arrived: opening on an undefined `editing` would
          make the form think it was creating, and Save would write a second reading instead
          of correcting this one. */}
      <PositionForm
        open={correctingId != null && !!correcting.data}
        editing={correcting.data}
        onClose={() => setCorrectingId(undefined)}
      />
    </>
  );
}
