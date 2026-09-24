import { useState } from 'react';
import { Button, Tag, Tooltip, Typography } from 'antd';
import { FileTextOutlined, LinkOutlined } from '@ant-design/icons';
import { useIntakeStatus, usePendingCompanyForPosition } from '../../intake/store';
import CompanyDrawer from '../companies/CompanyDrawer';
import IntakeItemDrawer from '../intake/IntakeItemDrawer';
import AttachCompanyModal from '../vessels/AttachCompanyModal';
import OriginalEmail from '../../components/OriginalEmail';

/**
 * Who reported a position, as something to open rather than a name to read.
 *
 * <p>A firm on file opens its record — the question a reporter's name raises is who to ring,
 * and that answer is the firm's people and addresses. A reading nobody is named on, where the
 * signature it came from named a firm that is still waiting to be added, opens that question
 * instead: answering it is what puts the name here, for this reading and every other one the
 * same signature filed. Anything else prints as it always did, or not at all.
 *
 * <p>A named firm that is on her record in no capacity at all gets a button beside it to
 * relate the two. The firm that sends a list is usually who works her, which is worth having
 * on her record — but sending a list is not evidence of ownership, so the capacity is chosen
 * in the modal rather than assumed, the same rule the Intake review follows.
 *
 * <p>And whatever named her, an icon opens what was actually written — the email or the board
 * post the reading came from. A position is a broker's words turned into dates and a place,
 * and the words are the only thing that settles whether the turning was right.
 *
 * <p>Carries its own drawers, so the vessel drawer and the Open fleet table offer the same
 * link without either one growing a company drawer of its own for it. Clicks stop here, since
 * on the table the row underneath opens the vessel.
 */
export default function ReportedBy({
  positionId,
  companyId,
  companyName,
  prefix = '',
  vesselId,
  vesselName,
  linked,
  mailMessageId,
  feedItemId,
}: {
  positionId: number;
  vesselId: number;
  vesselName: string;
  /** Whether the firm is already on her record; the relate button shows only on false. */
  linked?: boolean;
  /** The email or board post the reading came from; the source icon shows when either is set. */
  mailMessageId?: number;
  feedItemId?: number;
  companyId?: number;
  companyName?: string;
  /** Printed before the name — the drawer says "by", the table's column header already does. */
  prefix?: string;
}) {
  const intakeEnabled = useIntakeStatus().data?.enabled ?? false;
  // Only asked where nobody is named: a named reporter is an answer, and the table would
  // otherwise ask once per row.
  const pending = usePendingCompanyForPosition(positionId, intakeEnabled && companyId == null);
  const [openCompanyId, setOpenCompanyId] = useState<number>();
  const [openItemId, setOpenItemId] = useState<number>();
  const [relating, setRelating] = useState(false);
  const [reading, setReading] = useState(false);
  const item = pending.data;
  const hasSource = mailMessageId != null || feedItemId != null;

  if (companyId == null && !item && !hasSource) return null;

  return (
    <span onClick={(e) => e.stopPropagation()}>
      {companyId != null ? (
        <Typography.Link type="secondary" onClick={() => setOpenCompanyId(companyId)}>
          {prefix}
          {companyName ?? 'the reporting firm'}
        </Typography.Link>
      ) : (
        item && (
          <Tooltip title="The signature this was read from names a firm that is not on file yet. Review the question to add it, and it becomes the reporter here.">
            <Typography.Link type="warning" onClick={() => setOpenItemId(item.id)}>
              {prefix}
              {item.subjectLabel ?? 'an unknown firm'} <Tag color="orange">not on file · review</Tag>
            </Typography.Link>
          </Tooltip>
        )
      )}
      {companyId != null && linked === false && (
        <Tooltip title={`${companyName ?? 'This firm'} is not on ${vesselName}'s record. Relate them — as owner, exclusive broker or broker.`}>
          <Button
            size="small"
            type="text"
            icon={<LinkOutlined />}
            aria-label="Relate the reporting firm to the vessel"
            onClick={() => setRelating(true)}
          />
        </Tooltip>
      )}
      {hasSource && (
        <Tooltip title={mailMessageId != null ? 'Read the original email' : 'Read the original post'}>
          <Button
            size="small"
            type="text"
            icon={<FileTextOutlined />}
            aria-label="Read the original text"
            onClick={() => setReading(true)}
          />
        </Tooltip>
      )}
      {reading && (
        <OriginalEmail
          open
          mailMessageId={mailMessageId}
          feedItemId={mailMessageId != null ? undefined : feedItemId}
          onClose={() => setReading(false)}
        />
      )}
      {companyId != null && relating && (
        <AttachCompanyModal
          open
          vesselId={vesselId}
          vesselName={vesselName}
          company={{ id: companyId, name: companyName ?? 'the reporting firm' }}
          onClose={() => setRelating(false)}
        />
      )}
      {openCompanyId != null && (
        <CompanyDrawer companyId={openCompanyId} onClose={() => setOpenCompanyId(undefined)} />
      )}
      {openItemId != null && (
        <IntakeItemDrawer itemId={openItemId} onClose={() => setOpenItemId(undefined)} />
      )}
    </span>
  );
}
