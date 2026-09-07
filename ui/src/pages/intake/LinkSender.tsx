import { useState } from 'react';
import { Alert, Button, Card, Select, Space, Tag, Typography, message } from 'antd';
import { ApartmentOutlined } from '@ant-design/icons';
import { useIntakeMutations } from '../../intake/store';
import type { IntakeItemResponse } from '../../api/intake';

/**
 * The capacities a company can act in on a hull.
 *
 * <b>Owner is not one of the broker roles and the difference is not cosmetic.</b> Choosing it
 * displaces whoever is on the record as owner, which reassigns the ship. The two broker roles
 * sit alongside ownership and say who is working her — which is the fact a position list
 * usually carries, since the broker sending the list is very often not the owner.
 */
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
 * Renders nothing when there is nothing to offer: no sender company resolved, or the sender
 * is already the owner on file.
 */
export default function LinkSender({
  item,
  ownerId,
  ownerName,
  onOpenCompany,
}: {
  item: IntakeItemResponse;
  /** The owner currently on the vessel's record, if any. */
  ownerId?: number;
  ownerName?: string;
  /** Opens the firm over this drawer — deciding who they are is part of deciding the link. */
  onOpenCompany: (id: number) => void;
}) {
  const { linkSender } = useIntakeMutations();
  const [role, setRole] = useState<string>('broker');

  const sender = item.senderCompanyId;
  if (!sender) return null;
  // Nothing to offer: the firm that sent the list is already the owner on file.
  if (ownerId && ownerId === sender) return null;

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
        <Space wrap size={8}>
          <Typography.Link strong onClick={() => onOpenCompany(sender)}>
            {item.senderCompanyName}
          </Typography.Link>
          <Tag color="blue">sent the email</Tag>
          {ownerName ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              on file, she is owned by{' '}
              {ownerId ? (
                <Typography.Link onClick={() => onOpenCompany(ownerId)}>{ownerName}</Typography.Link>
              ) : (
                ownerName
              )}
            </Typography.Text>
          ) : (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              no owner on file for her
            </Typography.Text>
          )}
        </Space>

        {!item.vesselId ? (
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
