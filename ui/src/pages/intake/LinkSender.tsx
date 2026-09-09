import { useState } from 'react';
import { Alert, Button, Card, Select, Space, Tag, Typography, message } from 'antd';
import { ApartmentOutlined } from '@ant-design/icons';
import { useIntakeMutations } from '../../intake/store';
import { CAPACITIES, DEFAULT_CAPACITY, ROLE_WORDS } from './capacities';
import type { IntakeItemResponse } from '../../api/intake';
import type { VesselCompanyLinkResponse } from '../../api/types';

// The three capacities and their wording live in capacities.ts, shared with the modal behind
// "Not this ship — create her", which asks the same question about a hull that does not exist
// yet. Two copies would drift, and the one that drifted would be the one describing `owner`.
const ROLES = CAPACITIES;

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
  const [role, setRole] = useState<string>(DEFAULT_CAPACITY);

  // Every distinct firm behind this item. One question about a hull is raised by however many
  // emails mention her, so two brokers can be sitting on the same one - and both are worth
  // keeping, because who works her is exactly what the record is for. Deduped by company: a
  // broker who sent the same list twice is one firm, not two offers to attach him.
  const senders = (() => {
    const seen = new Map<number, { id: number; name?: string }>();
    for (const src of item.sources ?? []) {
      if (src.senderCompanyId != null && !seen.has(src.senderCompanyId)) {
        seen.set(src.senderCompanyId, { id: src.senderCompanyId, name: src.senderCompanyName });
      }
    }
    // The item's own sender, for rows raised before sources existed and for the list view.
    if (seen.size === 0 && item.senderCompanyId != null) {
      seen.set(item.senderCompanyId, {
        id: item.senderCompanyId,
        name: item.senderCompanyName,
      });
    }
    return [...seen.values()];
  })();

  const [pickedSender, setPickedSender] = useState<number | undefined>(senders[0]?.id);
  const sender = pickedSender ?? senders[0]?.id;
  const senderName = senders.find((x) => x.id === sender)?.name ?? item.senderCompanyName;
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
              {senderName}
            </Typography.Link>
            <Tag color="blue">
              sent {senders.length > 1 ? 'one of these emails' : 'the email'}
            </Tag>
            {existing && (
              <Tag color="green">already on her as {ROLE_WORDS[existing.role] ?? existing.role}</Tag>
            )}
          </Space>
        )}

        {/* More than one firm wrote about her before anybody reviewed the question. Each is
            attachable on its own terms - they may well work her in different capacities. */}
        {senders.length > 1 && (
          <Space wrap size={4}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {senders.length} firms sent these emails:
            </Typography.Text>
            <Select
              size="small"
              value={sender}
              onChange={setPickedSender}
              style={{ minWidth: 210 }}
              options={senders.map((x) => ({ value: x.id, label: x.name ?? `#${x.id}` }))}
            />
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
            Nothing to add — {senderName} is already on her as{' '}
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
                    { id: item.id, body: { role, companyId: sender } },
                    {
                      onSuccess: () =>
                        message.success(
                          `${senderName} attached as ${chosen?.label.toLowerCase()}.`,
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
