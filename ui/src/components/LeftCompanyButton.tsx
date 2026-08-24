import { App, Button, Popconfirm, Tooltip } from 'antd';
import type { ButtonProps } from 'antd';
import { UserDeleteOutlined } from '@ant-design/icons';
import { usePersonMutations } from '../api/hooks';
import type { PersonResponse } from '../api/types';

/**
 * Mark somebody as no longer working at the company we have them filed under.
 *
 * The one control that reaches every address and number a person has at once, which is the
 * point of it: departure is a fact about the person, and flagging their five addresses one
 * by one is the same statement made five times — with the sixth, added next month, quietly
 * missing it. Clearing the flag brings all of them back together.
 *
 * Deliberately not a delete and not a company change. Circulation history references the
 * person, the addresses stay the right ones to search the mailbox for, and "who did we deal
 * with there before?" keeps its answer. Only the mail stops.
 *
 * Behind a confirmation on the way in, none on the way out: taking somebody off every
 * circulation is worth a deliberate second, while putting them back is not.
 */
export default function LeftCompanyButton({
  p,
  size = 'small',
  type = 'text',
  onChanged,
}: {
  p: PersonResponse;
  /** Sized for a row by default; the person form asks for the full-size version. */
  size?: ButtonProps['size'];
  type?: ButtonProps['type'];
  /**
   * The person as the server describes them after the flag moved. A list re-renders from
   * its own refetch and needs nothing here, but a form holds a snapshot of the row that was
   * clicked — without this the button it sits in would still be offering what it just did.
   */
  onChanged?: (p: PersonResponse) => void;
}) {
  const { setHasLeft } = usePersonMutations();
  const { message } = App.useApp();

  const where = p.companyName ?? 'this company';
  const apply = () =>
    setHasLeft.mutate(
      { id: p.id, hasLeft: !p.hasLeft },
      {
        onSuccess: (updated) => {
          onChanged?.(updated);
          message.success(
            p.hasLeft
              ? `${p.fullName} is back at ${where} — their addresses can be circulated to again`
              : `${p.fullName} marked as having left ${where} — their addresses are now out of every circulation`,
          );
        },
      },
    );

  const button = (
    <Button
      type={type}
      size={size}
      aria-label={p.hasLeft ? `Mark ${p.fullName} as still here` : `Mark ${p.fullName} as having left`}
      loading={setHasLeft.isPending}
      icon={<UserDeleteOutlined style={p.hasLeft ? { color: '#cf1322' } : undefined} />}
      onClick={p.hasLeft ? apply : undefined}
    >
      {p.hasLeft ? 'still here?' : 'left'}
    </Button>
  );

  if (p.hasLeft) {
    return (
      <Tooltip title={`Marked as having left ${where}. Click to put them back — every address of theirs returns to circulations.`}>
        {button}
      </Tooltip>
    );
  }

  return (
    // No Tooltip around the trigger: nesting one inside Popconfirm makes the two popups
    // fight over it. The confirm text carries the explanation instead.
    <Popconfirm
      title={`${p.fullName} has left ${where}?`}
      description="Every address and number of theirs comes out of circulations — including any already sitting on a saved list or the current one. Nothing is deleted, and you can undo this."
      okText="They have left"
      onConfirm={apply}
    >
      {button}
    </Popconfirm>
  );
}
