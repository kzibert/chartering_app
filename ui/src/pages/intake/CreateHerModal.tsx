import { useEffect, useState } from 'react';
import { Alert, Button, Modal, Select, Space, Typography } from 'antd';
import { CAPACITIES, DEFAULT_CAPACITY } from './capacities';

/**
 * "Not this ship — create her", and the one question that has to be asked while it is.
 *
 * <b>Why the capacity is asked here rather than afterwards.</b> Creating her from a review item
 * produces a hull with nobody on her at all — no owner, no broker — because everything the
 * email said about her was particulars and the only company in the picture is the firm that
 * sent it. That firm is almost always worth recording: a position list arrives from a broker
 * who works her, and "who do I ring about this ship" is the question her record exists to
 * answer. Before this modal the only way to record it was to notice the card further down the
 * drawer, and the drawer closes the moment she is created — so the fact was there, free, on
 * screen, and routinely lost.
 *
 * <b>But it is asked, not assumed.</b> Sending a position list is not evidence of ownership and
 * most lists come from brokers, so there is no capacity safe enough to default to silently.
 * Leaving without a link is a first-class answer and has its own button rather than being a
 * cancel — a person who does not know in what capacity the sender works her should be able to
 * create the ship and move on, and guessing on their behalf would put a wrong fact on a record
 * that looks checked.
 *
 * <b>Two writes, not one.</b> The caller fires the resolve and then the link, each keeping its
 * own change set, which is the same rule the rest of this drawer follows: the web's figures and
 * the email's are separate writes so the History tab can always say which origin a value came
 * from. One combined endpoint would save a round trip and lose that.
 */
export default function CreateHerModal({
  open,
  busy,
  senderCompanyName,
  matchedVesselName,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  busy: boolean;
  /** The firm the sending address resolved to. Absent when the sender is not linked to one. */
  senderCompanyName?: string;
  /** The hull the email was matched against, for saying what is about to be withdrawn. */
  matchedVesselName?: string;
  onCancel: () => void;
  /** `role` absent means create her and attach nobody. */
  onConfirm: (role?: string) => void;
}) {
  const [role, setRole] = useState<string>(DEFAULT_CAPACITY);

  // Back to the ordinary answer each time it opens. A capacity left over from the last ship
  // reviewed is a stale answer to a question about a different one.
  useEffect(() => {
    if (open) setRole(DEFAULT_CAPACITY);
  }, [open]);

  const chosen = CAPACITIES.find((r) => r.value === role);

  return (
    <Modal
      open={open}
      onCancel={onCancel}
      title="She is a different ship"
      // Three answers, so the default ok/cancel pair cannot express it.
      footer={
        <Space wrap>
          <Button onClick={onCancel}>Cancel</Button>
          <Button loading={busy} onClick={() => onConfirm(undefined)}>
            Create her without linking
          </Button>
          {senderCompanyName && (
            <Button type="primary" loading={busy} onClick={() => onConfirm(role)}>
              Create her and attach {senderCompanyName}
            </Button>
          )}
        </Space>
      }
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Paragraph style={{ marginBottom: 0 }}>
          Creates the vessel the email describes, as her own record, and files the position on
          her. The reading this email put on{' '}
          {matchedVesselName ? (
            <Typography.Text strong>{matchedVesselName}</Typography.Text>
          ) : (
            'the matched hull'
          )}{' '}
          is withdrawn — marked, not deleted, so her history still says what was reported and
          when.
        </Typography.Paragraph>

        {senderCompanyName ? (
          <>
            <Typography.Text>
              In what capacity does <Typography.Text strong>{senderCompanyName}</Typography.Text>{' '}
              act on the ship being created?
            </Typography.Text>
            <Select
              value={role}
              onChange={setRole}
              style={{ minWidth: 220 }}
              options={CAPACITIES.map((r) => ({ value: r.value, label: r.label, title: r.hint }))}
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {chosen?.hint}
            </Typography.Text>
            {/* She is brand new, so owner displaces nobody — but it is still a claim about who
                owns her rather than about who sent the email, and on a record with nothing else
                on it that claim is the only thing anyone will ever see. */}
            {role === 'owner' && (
              <Alert
                type="warning"
                showIcon
                message={`This records ${senderCompanyName} as the owner of the new ship.`}
                description="Sending a position list is not evidence of ownership — most lists come from brokers. Choose a broker role unless you know this firm owns her."
              />
            )}
          </>
        ) : (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            The sender of this email is not linked to a company, so there is nobody to attach.
            Link the address on the Mailbox tab and it will be recognised from then on — or
            attach a firm on her own record once she exists.
          </Typography.Text>
        )}
      </Space>
    </Modal>
  );
}
