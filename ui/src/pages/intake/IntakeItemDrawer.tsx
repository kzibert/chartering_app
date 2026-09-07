import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Checkbox,
  Descriptions,
  Drawer,
  Divider,
  Empty,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import dayjs from 'dayjs';
import VesselSelect from '../../components/VesselSelect';
import { useIntakeItem, useIntakeMutations } from '../../intake/store';
import { kindMeta } from './labels';
import type { FieldDiff, IntakeAction, IntakeItemResponse } from '../../api/intake';

interface Props {
  itemId?: number;
  onClose: () => void;
}

/**
 * One question, answered.
 *
 * <b>This is the screen the feature was asked for</b>, and its shape is the argument: what
 * the database holds, beside what the email said, one row per field, with a tick against
 * each. Accept all is one button because that is the common answer — a broker's own list is
 * usually righter than a record nobody has touched since the ship was bought — and the
 * per-field ticks are there for the case that makes the whole screen necessary, which is the
 * one figure in an otherwise good reading that is plainly wrong.
 *
 * Three buttons rather than two, and the middle one is not a rejection: on a new vessel it
 * links the position to a hull already on file, and on a duplicate cargo it keeps the cargo
 * as a row of its own. Both of those write. Only Discard writes nothing.
 */
export default function IntakeItemDrawer({ itemId, onClose }: Props) {
  const query = useIntakeItem(itemId);
  const { resolve } = useIntakeMutations();
  const item = query.data;

  const diffs = useMemo<FieldDiff[]>(() => {
    if (!item) return [];
    return (item.kind === 'VESSEL_FIELDS' ? item.payload?.diffs : undefined) ?? [];
  }, [item]);

  const [chosen, setChosen] = useState<string[]>([]);
  const [linkTo, setLinkTo] = useState<number>();

  // Everything ticked when the drawer opens: the common answer is "the list is right", and a
  // screen that starts with nothing selected makes the common answer the most clicking.
  useEffect(() => {
    setChosen(diffs.map((d) => d.field));
  }, [diffs]);

  useEffect(() => {
    setLinkTo(undefined);
  }, [itemId]);

  if (!itemId) return null;

  const pending = item?.status === 'PENDING';

  const answer = (action: IntakeAction) => {
    if (!item) return;
    if (item.kind === 'NEW_VESSEL' && action === 'ALTERNATIVE' && linkTo == null) {
      message.warning('Choose the vessel this position belongs to.');
      return;
    }
    if (item.kind === 'VESSEL_FIELDS' && action === 'ACCEPT' && chosen.length === 0) {
      message.warning('Tick at least one field, or use Keep what we have.');
      return;
    }
    resolve.mutate(
      {
        id: item.id,
        body: {
          action,
          fields: item.kind === 'VESSEL_FIELDS' ? chosen : undefined,
          vesselId: item.kind === 'NEW_VESSEL' ? linkTo : undefined,
        },
      },
      {
        onSuccess: (updated) => {
          message.success(updated.resolutionNote || 'Done.');
          onClose();
        },
      },
    );
  };

  return (
    <Drawer
      open
      width={720}
      onClose={onClose}
      title={
        item ? (
          <Space size={8} wrap>
            <Tag color={kindMeta(item.kind).colour}>{kindMeta(item.kind).label}</Tag>
            <span>{item.subjectLabel || '—'}</span>
          </Space>
        ) : (
          'Loading…'
        )
      }
      footer={
        item && pending ? (
          <Footer item={item} busy={resolve.isPending} onAnswer={answer} />
        ) : undefined
      }
      loading={query.isLoading}
    >
      {item && (
        <>
          <SourceEmail item={item} />

          {!pending && (
            <Alert
              type="success"
              showIcon
              style={{ marginBottom: 16 }}
              message={item.status === 'ACCEPTED' ? 'Answered' : 'Discarded'}
              description={
                <>
                  {item.resolutionNote}
                  {item.resolvedAt && (
                    <>
                      <br />
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {dayjs(item.resolvedAt).format('D MMM YYYY HH:mm')}
                        {item.resolvedBy ? ` · ${item.resolvedBy}` : ''}
                      </Typography.Text>
                    </>
                  )}
                </>
              }
            />
          )}

          {item.payload == null ? (
            <Alert
              type="warning"
              showIcon
              message="This item was raised by an older version and can no longer be applied."
              description="Discard it and read the email again from the log."
            />
          ) : item.kind === 'VESSEL_FIELDS' ? (
            <VesselFieldsBody
              item={item}
              diffs={diffs}
              chosen={chosen}
              onChange={setChosen}
              editable={pending}
            />
          ) : item.kind === 'NEW_VESSEL' ? (
            <NewVesselBody item={item} linkTo={linkTo} onLink={setLinkTo} editable={pending} />
          ) : (
            <CargoMergeBody item={item} />
          )}
        </>
      )}
    </Drawer>
  );
}

/** Which email this came out of. A question about the market, not about a spreadsheet. */
function SourceEmail({ item }: { item: IntakeItemResponse }) {
  return (
    <Descriptions size="small" column={1} style={{ marginBottom: 16 }}>
      <Descriptions.Item label="From">
        {item.fromName ? `${item.fromName} <${item.fromAddress}>` : item.fromAddress || '—'}
      </Descriptions.Item>
      <Descriptions.Item label="Subject">{item.mailSubject || '(no subject)'}</Descriptions.Item>
      <Descriptions.Item label="Arrived">
        {item.receivedAt ? dayjs(item.receivedAt).format('D MMM YYYY HH:mm') : '—'}
      </Descriptions.Item>
    </Descriptions>
  );
}

/**
 * On file, against what the email said.
 *
 * Only the disagreements are ticked: the fields that were empty have already been written,
 * because filling a blank column destroys nothing and stopping to ask about each would put
 * thousands of items in a queue whose every answer is yes. They are listed underneath so the
 * screen accounts for everything the email said rather than showing three conflicts and
 * silently having changed five other columns.
 */
function VesselFieldsBody({
  item,
  diffs,
  chosen,
  onChange,
  editable,
}: {
  item: IntakeItemResponse;
  diffs: FieldDiff[];
  chosen: string[];
  onChange: (fields: string[]) => void;
  editable: boolean;
}) {
  const filled = item.payload?.filled ?? [];
  const renamed = diffs.some((d) => d.field === 'name');

  return (
    <>
      <Space wrap style={{ marginBottom: 12 }}>
        <Typography.Text type="secondary">
          {item.payload?.matchedBy ?? 'Matched'} · her position has already been filed.
        </Typography.Text>
      </Space>

      {renamed && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          message="This looks like a rename"
          description="She was matched by IMO and the email calls her something else. Accepting the name change files the name she is losing as a former name, so the older position lists still find her."
        />
      )}

      <Table<FieldDiff>
        rowKey={(d) => d.field}
        size="small"
        pagination={false}
        dataSource={diffs}
        locale={{ emptyText: <Empty description="Nothing disagrees." /> }}
        rowSelection={
          editable
            ? {
                selectedRowKeys: chosen,
                onChange: (keys) => onChange(keys as string[]),
                columnTitle: (
                  <Tooltip title="Tick what the email got right">
                    <span>Use</span>
                  </Tooltip>
                ),
              }
            : undefined
        }
        columns={[
          { title: 'Field', dataIndex: 'label', key: 'label', width: 150 },
          {
            title: 'On file',
            dataIndex: 'current',
            key: 'current',
            render: (v: string) => <Typography.Text delete={false}>{v ?? '—'}</Typography.Text>,
          },
          {
            title: 'The email says',
            dataIndex: 'incoming',
            key: 'incoming',
            render: (v: string) => <Typography.Text strong>{v ?? '—'}</Typography.Text>,
          },
        ]}
      />

      {filled.length > 0 && (
        <>
          <Divider style={{ margin: '16px 0 8px' }} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Already filled in, because the record held nothing there:{' '}
            {filled.join(', ')}
          </Typography.Text>
        </>
      )}
    </>
  );
}

/**
 * A hull nothing on file answers to.
 *
 * The suggestions are the third matching tier, and they carry their evidence: IMO and name
 * are exact enough to file a position on, particulars are not — two 28,000-tonners built in
 * 2003 are two ships — so this offers rather than decides. Linking to one also remembers the
 * name the email used, which is what stops the next circular asking the same question.
 */
function NewVesselBody({
  item,
  linkTo,
  onLink,
  editable,
}: {
  item: IntakeItemResponse;
  linkTo?: number;
  onLink: (id?: number) => void;
  editable: boolean;
}) {
  const v = (item.payload?.vessel ?? {}) as Record<string, unknown>;
  const suggestions = item.payload?.suggestions ?? [];
  const show = (key: string) => {
    const value = v[key];
    return value === null || value === undefined || value === '' ? '—' : String(value);
  };

  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={`Nothing on file matched ${item.payload?.searchedBy ?? 'this vessel'}.`}
        description="Create her from what the email said, or point this position at a ship already on file — which is also the answer when she has simply been renamed."
      />

      <Descriptions size="small" column={2} bordered style={{ marginBottom: 16 }}>
        <Descriptions.Item label="Name">{show('name')}</Descriptions.Item>
        <Descriptions.Item label="IMO">{show('imo')}</Descriptions.Item>
        <Descriptions.Item label="DWT">{show('dwt')}</Descriptions.Item>
        <Descriptions.Item label="DWCC">{show('dwcc')}</Descriptions.Item>
        <Descriptions.Item label="Built">{show('built')}</Descriptions.Item>
        <Descriptions.Item label="Draft">{show('draft')}</Descriptions.Item>
        <Descriptions.Item label="Gear">{show('gearDescription')}</Descriptions.Item>
        <Descriptions.Item label="Flag">{show('flag')}</Descriptions.Item>
        <Descriptions.Item label="Opens" span={2}>
          {[show('openPort'), show('openArea'), show('openText')]
            .filter((s) => s !== '—')
            .join(' · ') || '—'}
        </Descriptions.Item>
      </Descriptions>

      {suggestions.length > 0 && (
        <>
          <Typography.Title level={5} style={{ marginBottom: 8 }}>
            Could she be one of these?
          </Typography.Title>
          <Space direction="vertical" size={4} style={{ width: '100%', marginBottom: 12 }}>
            {suggestions.map((s) => (
              <Space key={s.vesselId} wrap>
                <Button
                  size="small"
                  type={linkTo === s.vesselId ? 'primary' : 'default'}
                  disabled={!editable}
                  onClick={() => onLink(linkTo === s.vesselId ? undefined : s.vesselId)}
                >
                  {s.name}
                </Button>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {s.imoNumber ? `IMO ${s.imoNumber} · ` : ''}
                  {s.reason}
                </Typography.Text>
              </Space>
            ))}
          </Space>
        </>
      )}

      {editable && (
        <>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Or find her yourself — the search covers former names too:
          </Typography.Text>
          <div style={{ marginTop: 6 }}>
            <VesselSelect value={linkTo} onChange={onLink} allowClear />
          </div>
        </>
      )}
    </>
  );
}

/**
 * A cargo that looks like one already in hand.
 *
 * A merge fills the existing cargo's empty fields and leaves every disagreement alone — the
 * two readings are two brokers describing one enquiry and neither of them is the charterer,
 * so there is no reason to believe the second over the first. The differences are shown
 * rather than hidden, because "these two firms are quoting different numbers" is itself
 * worth knowing and would be invisible if one had silently won.
 */
function CargoMergeBody({ item }: { item: IntakeItemResponse }) {
  const reasons = item.payload?.reasons ?? [];
  const wouldFill = item.payload?.wouldFill ?? [];
  const differing = item.payload?.differing ?? [];

  return (
    <>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message={`Looks like: ${item.payload?.candidateLabel ?? 'a cargo already in hand'}`}
        description={
          reasons.length > 0 ? (
            <ul style={{ margin: '4px 0 0 16px', padding: 0 }}>
              {reasons.map((r) => (
                <li key={r}>{r}</li>
              ))}
            </ul>
          ) : undefined
        }
      />

      {wouldFill.length > 0 && (
        <Typography.Paragraph style={{ marginBottom: 12 }}>
          Merging would fill in: <Typography.Text strong>{wouldFill.join(', ')}</Typography.Text>
        </Typography.Paragraph>
      )}

      {differing.length > 0 && (
        <>
          <Typography.Title level={5} style={{ marginBottom: 8 }}>
            These differ — the cargo keeps what it has
          </Typography.Title>
          <Table<FieldDiff>
            rowKey={(d) => d.field}
            size="small"
            pagination={false}
            dataSource={differing}
            columns={[
              { title: 'Field', dataIndex: 'label', key: 'label', width: 170 },
              { title: 'On file', dataIndex: 'current', key: 'current' },
              { title: 'This email', dataIndex: 'incoming', key: 'incoming' },
            ]}
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Both readings stay visible: the merged cargo lists every broker who has sent it.
          </Typography.Text>
        </>
      )}

      {wouldFill.length === 0 && differing.length === 0 && (
        <Empty description="The cargo on file already holds everything this email said." />
      )}
    </>
  );
}

/** The three answers, worded per kind so no button says "accept" without saying to what. */
function Footer({
  item,
  busy,
  onAnswer,
}: {
  item: IntakeItemResponse;
  busy: boolean;
  onAnswer: (action: IntakeAction) => void;
}) {
  const words: Record<string, { accept: string; alternative: string; discard: string }> = {
    NEW_VESSEL: {
      accept: 'Create the vessel',
      alternative: 'Link to the chosen ship',
      discard: 'Discard',
    },
    VESSEL_FIELDS: {
      accept: 'Update the ticked fields',
      alternative: '',
      discard: 'Keep what we have',
    },
    CARGO_MERGE: {
      accept: 'Merge',
      alternative: 'Keep separate',
      discard: 'Discard',
    },
  };
  const w = words[item.kind];

  return (
    <Space wrap>
      <Button type="primary" loading={busy} onClick={() => onAnswer('ACCEPT')}>
        {w.accept}
      </Button>
      {w.alternative && (
        <Button loading={busy} onClick={() => onAnswer('ALTERNATIVE')}>
          {w.alternative}
        </Button>
      )}
      <Popconfirm
        title={w.discard}
        description="Nothing will be written. The email stays in the log."
        onConfirm={() => onAnswer('DISCARD')}
        okText="Yes"
        cancelText="No"
      >
        <Button danger={item.kind !== 'VESSEL_FIELDS'} loading={busy}>
          {w.discard}
        </Button>
      </Popconfirm>
    </Space>
  );
}
