import { useState } from 'react';
import { Alert, Button, Card, Select, Space, Tag, Typography, message } from 'antd';
import { ApartmentOutlined } from '@ant-design/icons';
import { useIntakeMutations } from '../../intake/store';
import type { IntakeItemResponse } from '../../api/intake';
import type { VesselCompanyLinkResponse } from '../../api/types';

/**
 * The capacities a company can act in on a hull.
 *
 * <b>Owner is not one of the broker roles and the difference is not cosmetic.</b> Choosing it
 * displaces whoever is on the record as owner, which reassigns the ship. The two broker roles
 * sit alongside ownership and say who is working her — which is the fact a position list
 * usually carries, since the broker sending the list is very often not the owner.
 */
/** The capacity words, for printing a link that already exists. */
const ROLE_WORDS: Record<string, string> = {
  owner: 'owner',
  exclusive_broker: 'exclusive broker',
  broker: 'broker',
};

const ROLES = [
  {
    value: 'broker',
    label: 'Broker',
    hint: 'Works this vessel. Sits alongside the owner and displaces nothing — the ordinary answer for a broker whose list this came from.',
  },
  {
    value: 'exclusive_broker',
    label: 'Exclusive broker',
    hint: 'Works her exclusively. Only one per vessel: whoever held it is demoted to broker rather than the save failing.',
  },
  {
    value: 'owner',
    label: 'Owner',
    hint: 'Displaces the owner currently on the record. Choose this only if you know the ship has changed hands — it is a claim about who owns her, not about who sent the email.',
  },
];

/**
 * Attach the company that sent the email to the vessel it was about.
 *
 * <b>Why the Intake tab is the right place for it.</b> This is the moment the fact exists: a
 * list arrives from a broker, about a hull whose owner on file is somebody else, and the
 * useful thing to record is that this firm is working her. Later, on the vessel's own screen,
 * the email is gone and so is the reason to add the link. Doing it here costs one click while
 * the evidence is on the page.
 *
 * <b>It writes on its own endpoint, like the other actions on this drawer.</b> Linking a
 * company is not part of accepting the email's figures and must not ride along with them —
 * the same rule the record forms follow, where delete, ban and confirm each fire their own
 * call rather than being folded into Save.
 *
 * <b>Every company already on her is listed, and the offer only appears when there is
 * something to offer.</b> Without the list the card asked the same question every morning:
 * a broker whose position lists arrive weekly was already attached, and nothing on screen
 * said so, so "attach this firm" sat there inviting a click that would rewrite a link that
 * was already right. The links are the context that turns the question into a real one —
 * who is on her, in what capacity, and whether the sender is among them.
 *
 * Renders nothing when there is nothing to say: no sender company resolved and nobody linked.
 */
export default function LinkSender({
  item,
  ownerId,
  ownerName,
  links,
  onOpenCompany,
  onOpenVessel,
}: {
  item: IntakeItemResponse;
  /** The owner currently on the vessel's record, if any. */
  ownerId?: number;
  ownerName?: string;
  /** Every company on her, owner and brokers alike — as her own record holds them. */
  links?: VesselCompanyLinkResponse[];
  /** Opens the firm over this drawer — deciding who they are is part of deciding the link. */
  onOpenCompany: (id: number) => void;
  /** Opens her record, for the fuller picture this card only summarises. */
  onOpenVessel?: () => void;
}) {
  const { linkSender } = useIntakeMutations();
  const [role, setRole] = useState<string>('broker');

  const sender = item.senderCompanyId;
  const onHer = links ?? [];
  // Owner lives on the vessel rather than in the link table, so it has to be folded in or
  // the list would show a ship's brokers and not her owner.
  const all: { companyId: number; companyName?: string; role: string }[] = [
    ...(ownerId ? [{ companyId: ownerId, companyName: ownerName, role: 'owner' }] : []),
    ...onHer
      .filter((l) => l.role !== 'owner')
      .map((l) => ({ companyId: l.companyId, companyName: l.companyName, role: l.role })),
  ];
  const existing = sender ? all.find((l) => l.companyId === sender) : undefined;

  if (!sender && all.length === 0) return null;

  const chosen = ROLES.find((r) => r.value === role);

  return (
    <Card
      size="small"
      style={{ marginBottom: 16 }}
      title={
        <Space>
          <ApartmentOutlined />
          The company that sent this
        </Space>
      }
    >
      <Space direction="vertical" size={8} style={{ width: '100%' }}>
        {sender && (
          <Space wrap size={8}>
            <Typography.Link strong onClick={() => onOpenCompany(sender)}>
              {item.senderCompanyName}
            </Typography.Link>
            <Tag color="blue">sent the email</Tag>
            {existing && (
              <Tag color="green">already on her as {ROLE_WORDS[existing.role] ?? existing.role}</Tag>
            )}
          </Space>
        )}

        {/* Who is on her already. The question "should this firm be attached" cannot be
            answered without it, and asking it every morning against a broker who was
            attached months ago is how a card stops being read. */}
        <div>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            On her record:
          </Typography.Text>{' '}
          {all.length === 0 ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              nobody yet
            </Typography.Text>
          ) : (
            <Space wrap size={4}>
              {all.map((l) => (
                <Tag
                  key={`${l.companyId}-${l.role}`}
                  color={l.role === 'owner' ? 'purple' : 'default'}
                  style={{ marginInlineEnd: 0 }}
                >
                  <Typography.Link
                    style={{ fontSize: 12 }}
                    onClick={() => onOpenCompany(l.companyId)}
                  >
                    {l.companyName ?? `#${l.companyId}`}
                  </Typography.Link>
                  {' · '}
                  {ROLE_WORDS[l.role] ?? l.role}
                </Tag>
              ))}
            </Space>
          )}
          {onOpenVessel && (
            <>
              {' '}
              <Typography.Link style={{ fontSize: 12 }} onClick={onOpenVessel}>
                open her record
              </Typography.Link>
            </>
          )}
        </div>

        {!sender ? null : existing ? (
          /* Already attached, so there is nothing to add. Changing a capacity is a decision
             about the relationship rather than about this email, and it belongs on her own
             record where every link is in view - not on a card that happens to be open
             because a circular arrived. */
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Nothing to add — {item.senderCompanyName} is already on her as{' '}
            {ROLE_WORDS[existing.role] ?? existing.role}. Change the capacity on her own record
            if it is wrong.
          </Typography.Text>
        ) : !item.vesselId ? (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            Create her or link her to a ship on file first — there is no record to attach a
            company to yet.
          </Typography.Text>
        ) : (
          <>
            <Space wrap>
              <Select
                value={role}
                onChange={setRole}
                style={{ minWidth: 190 }}
                options={ROLES.map((r) => ({
                  value: r.value,
                  label: r.label,
                  title: r.hint,
                }))}
              />
              <Button
                loading={linkSender.isPending}
                onClick={() =>
                  linkSender.mutate(
                    { id: item.id, body: { role } },
                    {
                      onSuccess: () =>
                        message.success(
                          `${item.senderCompanyName} attached as ${chosen?.label.toLowerCase()}.`,
                        ),
                    },
                  )
                }
              >
                Attach to this vessel
              </Button>
            </Space>

            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {chosen?.hint}
            </Typography.Text>

            {role === 'owner' && ownerName && (
              <Alert
                type="warning"
                showIcon
                message={`This will replace ${ownerName} as the owner on her record.`}
                description="Sending a position list is not evidence of ownership — most lists come from brokers. Choose a broker role unless you know she has changed hands."
              />
            )}
          </>
        )}
      </Space>
    </Card>
  );
}
