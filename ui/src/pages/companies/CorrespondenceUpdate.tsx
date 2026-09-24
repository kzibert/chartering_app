import { useState } from 'react';
import { Alert, Button, Tag, Tooltip } from 'antd';
import { MailOutlined } from '@ant-design/icons';
import { useIntakeStatus, usePendingCompanyItem } from '../../intake/store';
import IntakeItemDrawer from '../intake/IntakeItemDrawer';
import { MINOR_HINT } from '../intake/labels';
import type { IntakeItemResponse } from '../../api/intake';

/**
 * "Update from correspondence" — the company question, offered on the company's own record.
 *
 * <b>Why here as well as on the Intake tab.</b> Every circular a firm sends ends in its full
 * style, and most of what those blocks say beyond the record is worth having and not worth a
 * morning: a website moved, a new mobile, a colleague's job title. So those questions stopped
 * counting toward Needs review (they wait on Minor updates), and this is where they are meant to
 * be answered — on the record, when somebody has it open, from every email the firm has sent
 * aggregated into one set of details rather than one question per circular.
 *
 * Shown only while there is something the aggregate would still change. The item is compared
 * again with the record every time it is read, so a phone added by hand on the People tab
 * takes the button away rather than leaving it offering a write that changes nothing.
 *
 * Absent where the parser is off (the hosted instance): the questions are rows in the shared
 * database, but answering one goes through the intake endpoints, which answer 404 there.
 */
export default function CorrespondenceUpdate({ companyId }: { companyId: number }) {
  const status = useIntakeStatus();
  const enabled = status.data?.enabled === true;
  const pending = usePendingCompanyItem(companyId, enabled);
  const [open, setOpen] = useState(false);

  const item = pending.data;
  if (!enabled || !item || !hasSomethingToUpdate(item)) return null;

  const emails = item.sources?.length ?? 1;
  return (
    <>
      <Alert
        type={item.minor ? 'info' : 'warning'}
        showIcon
        icon={<MailOutlined />}
        style={{ marginBottom: 12 }}
        message={
          <>
            Their signatures carry details this record does not have
            {item.minor && (
              <Tooltip title={MINOR_HINT}>
                <Tag style={{ marginInlineStart: 8 }}>minor</Tag>
              </Tooltip>
            )}
          </>
        }
        description={`${item.summary ?? ''} — from ${emails} ${emails === 1 ? 'email' : 'emails'}.`}
        action={
          <Button size="small" type="primary" onClick={() => setOpen(true)}>
            Update from correspondence
          </Button>
        }
      />
      {open && <IntakeItemDrawer itemId={item.id} onClose={() => setOpen(false)} />}
    </>
  );
}

/**
 * Whether the aggregate would still change anything, read off the comparison the server made
 * against the record as it stands. A firm not on file is always something to decide.
 */
function hasSomethingToUpdate(item: IntakeItemResponse): boolean {
  const comparison = (item.payload as { comparison?: Comparison } | undefined)?.comparison;
  if (!comparison) return true;
  return (
    (comparison.fields?.length ?? 0) > 0 ||
    (comparison.people ?? []).some((p) => p.existingPersonId == null) ||
    (comparison.contacts ?? []).some((c) => c.existingContactId == null)
  );
}

interface Comparison {
  fields?: unknown[];
  people?: { existingPersonId?: number }[];
  contacts?: { existingContactId?: number }[];
}
