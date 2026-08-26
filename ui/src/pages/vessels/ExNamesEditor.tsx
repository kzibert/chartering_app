import { useState } from 'react';
import { Alert, Button, Input, List, Popconfirm, Space, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useVesselMutations } from '../../api/hooks';
import type { VesselExNameResponse } from '../../api/types';

interface Props {
  vesselId: number;
  exNames: VesselExNameResponse[];
}

/**
 * The names a vessel used to carry.
 *
 * **Each row writes on its own, not on Save**, which is the same rule the actions at the
 * foot of an edit form follow and for the same reason: these are rows in another table, and
 * folding them into the vessel's whole-record PUT would let a form opened five minutes ago
 * silently delete a ship's history while somebody was correcting her deadweight.
 *
 * The `backfill` tag is worth its space. Those names were read out of a free-text field by
 * a migration — "ELEMENTS / EX GUBERNATOR KAMCHATKI/ EX KATERINA" split into three — and
 * when a vessel looks wrong they are the first thing to check. A name somebody typed
 * carries no tag, because a person vouched for it.
 */
export default function ExNamesEditor({ vesselId, exNames }: Props) {
  const { addExName, removeExName } = useVesselMutations();
  const [draft, setDraft] = useState('');

  const add = () => {
    const name = draft.trim();
    if (!name) return;
    addExName.mutate({ id: vesselId, body: { name } }, { onSuccess: () => setDraft('') });
  };

  return (
    <>
      <Typography.Title level={5} style={{ marginTop: 24 }}>
        Former names
      </Typography.Title>
      <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
        Searching by any of these finds this vessel. Add one whenever a position list uses a
        name we do not hold — that is what stops the same hull being entered twice.
      </Typography.Paragraph>

      {exNames.length === 0 && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 8 }}
          message="No former names on file for this vessel."
        />
      )}

      <List
        size="small"
        dataSource={exNames}
        renderItem={(e) => (
          <List.Item
            actions={[
              <Popconfirm
                key="del"
                title="Remove this former name?"
                description="She stops being findable under it."
                onConfirm={() => removeExName.mutate({ id: vesselId, exNameId: e.id })}
              >
                <Button size="small" type="text" danger icon={<DeleteOutlined />} />
              </Popconfirm>,
            ]}
          >
            <Space size={6} wrap>
              {e.name}
              {e.source === 'backfill' && (
                <Tooltip title="Extracted from the vessel's name by the migration that split rename histories out of it. Worth a second look if this ship ever seems wrong.">
                  <Tag>backfill</Tag>
                </Tooltip>
              )}
              {e.renamedAt && (
                <Typography.Text type="secondary">renamed {e.renamedAt}</Typography.Text>
              )}
            </Space>
          </List.Item>
        )}
      />

      <Space.Compact style={{ width: '100%', marginTop: 8 }}>
        <Input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onPressEnter={add}
          placeholder="A name she used to carry"
        />
        <Button
          icon={<PlusOutlined />}
          onClick={add}
          loading={addExName.isPending}
          disabled={!draft.trim()}
        >
          Add
        </Button>
      </Space.Compact>
    </>
  );
}
