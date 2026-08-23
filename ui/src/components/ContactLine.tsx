import type { HTMLAttributes, ReactNode } from 'react';
import { Button, List, Popconfirm, Space, Tag, Tooltip, Typography } from 'antd';
import { DeleteOutlined, EditOutlined, HolderOutlined, RightOutlined } from '@ant-design/icons';
import CopyableValue from './CopyableValue';
import GreetingName from './GreetingName';
import AddToListButton from './AddToListButton';
import BanButton from './BanButton';
import MainContactButton from './MainContactButton';
import CircToggleButton from './CircToggleButton';
import NoCircToggleButton from './NoCircToggleButton';
import WorkingToggleButton from './WorkingToggleButton';
import { WhatsappChatLink, WhatsappCheckButton } from './WhatsappButtons';
import { useContactRowExpansion } from './ContactRowExpansion';
import { useContactMutations } from '../api/hooks';
import type { ContactResponse } from '../api/types';

/** How an address with no greeting is opened — MailTemplateService.NEUTRAL_SALUTATION. */
const NEUTRAL_SALUTATION = 'Good day';

/**
 * One email/phone line. When `showGreeting` is set (default), the person behind the address
 * is described: a copiable English greeting name, and their job title when one is on file.
 * Handy when the list isn't already grouped by person (company Contacts tab, vessel owner
 * contacts). A list that groups by person turns it off — both facts belong to the person,
 * and repeating them on each of their three rows says nothing the heading has not.
 *
 * A company-wide address (on a company, on nobody) carries a tag saying so. The opener it
 * will be merged with lives in the tag's tooltip rather than on the row: "company-wide"
 * already says the address has no person behind it, and a general greeting follows from
 * that — printing it beside every such row is a second label for the same fact.
 *
 * **Why the write controls hide.** There are nine of them, and rendering all nine on every
 * row turned a company with six contacts into fifty-odd buttons wrapping over two lines
 * each — a wall that had to be read before the addresses could be. They now open under the
 * row that was clicked, one row at a time (see `ContactRowExpansion`). The row itself keeps
 * only what is worth seeing while browsing: the value, the flags as tags, and the two
 * actions that read rather than write — add-to-list and the WhatsApp chat link.
 */
export default function ContactLine({
  ct,
  showGreeting = true,
  editing = false,
  highlight,
  dragHandle,
  onEdit,
  onDelete,
}: {
  ct: ContactResponse;
  showGreeting?: boolean;
  /** Substring to mark in the value — the term this row was searched by. */
  highlight?: string;
  /**
   * Gates every control that writes to the contact — main, circ, no-circ, not-working, the
   * WhatsApp check, edit, ban, delete — behind a click on the row, and shows the disclosure
   * that says so. Add-to-list is deliberately not gated: it only builds the local email
   * list. Neither is the WhatsApp chat link, which only opens a chat with a number already
   * flagged.
   */
  editing?: boolean;
  /**
   * Grip rendered at the head of the row while editing, for a list that lets contacts be
   * dragged elsewhere. A handle rather than the whole row: the row is a click target now,
   * and one that is both would make every click a gamble on whether the pointer moved.
   */
  dragHandle?: ReactNode;
  /** Supply to show an edit button. The caller owns the form, so one drawer renders one. */
  onEdit?: (ct: ContactResponse) => void;
  /** Supply to show a delete button (already behind a confirmation popup). */
  onDelete?: (ct: ContactResponse) => void;
}) {
  const { ban } = useContactMutations();
  const [open, toggleOpen] = useContactRowExpansion(ct.id);
  const expanded = editing && open;

  const summary = (
    <Space wrap size={4}>
      {showGreeting && ct.greetingName && (
        <GreetingName title={ct.title} name={ct.greetingName} type="secondary" />
      )}
      {/* The person's position, not the address's. Muted rather than tagged: the tags on
          this row are all flags that change how the address is treated, and a job title
          changes nothing — it is there to tell you who you are writing to. */}
      {showGreeting && ct.jobTitle && (
        <Tooltip title={`${ct.personName ?? 'This person'}'s position at ${ct.companyName ?? 'the company'}. It belongs to the person, so it shows on every address and number of theirs — edit it on the person, not on this row.`}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {ct.jobTitle}
          </Typography.Text>
        </Tooltip>
      )}
      {ct.personLeft && (
        <Tooltip
          title={`${ct.personName ?? 'This person'} no longer works at ${ct.companyName ?? 'this company'}, so this address is out of every circulation — it is skipped at send time even if it is already on a list. Clear it from the person, not from here: the flag covers all of their addresses at once.`}
        >
          <Tag color="red">left the company</Tag>
        </Tooltip>
      )}
      {ct.companyWide && (
        <Tooltip
          title={
            ct.greetingName
              ? `Belongs to ${ct.companyName ?? 'the company'} itself, not to a person. Circulars to it open "Dear ${ct.greetingName},".`
              : `Belongs to ${ct.companyName ?? 'the company'} itself, not to a person. Circulars to it open "${NEUTRAL_SALUTATION}," — which commits to no number, gender or role. Edit the contact to open with something else.`
          }
        >
          <Tag color="cyan">company-wide</Tag>
        </Tooltip>
      )}
      <Tag color={ct.contactKind === 'email' ? 'blue' : 'default'}>{ct.contactKind}</Tag>
      <CopyableValue value={ct.contactValue} highlight={highlight} />
      {ct.main && <Tag color="gold">main</Tag>}
      {ct.circ && (
        <Tooltip title="Used for circulations — collected instead of this person's other addresses">
          <Tag color="blue">circ</Tag>
        </Tooltip>
      )}
      {ct.noCirc && (
        <Tooltip title="Never circulated to — excluded from bulk collection and dropped again at send time. Still fine to write to by hand.">
          <Tag color="red">no circ</Tag>
        </Tooltip>
      )}
      {/* Outside the editing gate: reaching someone on WhatsApp is reading the contact,
          not editing it, and this is the point of having flagged the number at all. */}
      <WhatsappChatLink ct={ct} />
      {!ct.working && <Tag color="red">not working</Tag>}
      {ct.confirmed && <Tag color="success">confirmed</Tag>}
      {ct.banned && <Tag color="red">banned</Tag>}
      <AddToListButton ct={ct} />
    </Space>
  );

  if (!editing) {
    return <List.Item>{summary}</List.Item>;
  }

  return (
    <List.Item style={{ display: 'block' }}>
      <Space align="start" size={4} style={{ width: '100%' }}>
        {dragHandle}
        <Tooltip title={expanded ? 'Hide the controls for this contact' : 'Show the controls for this contact'}>
          <Button
            type="text"
            size="small"
            aria-label={expanded ? 'Hide controls' : 'Show controls'}
            aria-expanded={expanded}
            onClick={toggleOpen}
            icon={
              <RightOutlined
                style={{
                  fontSize: 10,
                  transition: 'transform .2s',
                  transform: expanded ? 'rotate(90deg)' : undefined,
                }}
              />
            }
          />
        </Tooltip>
        {/* The summary is a click target too, so hitting the address opens the row — aiming
            for a 16px caret to reach controls you can already see is busywork. */}
        <span onClick={toggleOpen} style={{ cursor: 'pointer' }}>
          {summary}
        </span>
      </Space>

      {expanded && (
        <div
          style={{
            marginTop: 6,
            marginInlineStart: 28,
            padding: '6px 8px',
            background: 'rgba(0,0,0,.02)',
            border: '1px solid rgba(0,0,0,.06)',
            borderRadius: 6,
          }}
        >
          <Space wrap size={4}>
            <MainContactButton ct={ct} />
            <CircToggleButton ct={ct} />
            <NoCircToggleButton ct={ct} />
            <WorkingToggleButton ct={ct} />
            <WhatsappCheckButton ct={ct} />
            {onEdit && (
              <Tooltip title="Edit contact">
                <Button
                  type="text"
                  size="small"
                  aria-label="Edit contact"
                  icon={<EditOutlined />}
                  onClick={() => onEdit(ct)}
                >
                  edit
                </Button>
              </Tooltip>
            )}
            <BanButton
              banned={ct.banned}
              loading={ban.isPending}
              size="small"
              onToggle={(b) => ban.mutate({ id: ct.id, banned: b })}
            />
            {onDelete && (
              // No Tooltip here: nesting one inside Popconfirm makes both popups fight
              // over the same trigger. The confirm title says what the button does.
              <Popconfirm title="Delete this contact?" onConfirm={() => onDelete(ct)}>
                <Button
                  type="text"
                  size="small"
                  danger
                  aria-label="Delete contact"
                  icon={<DeleteOutlined />}
                >
                  delete
                </Button>
              </Popconfirm>
            )}
          </Space>
        </div>
      )}
    </List.Item>
  );
}

/** The grip a draggable list passes as `dragHandle`, kept here so every one matches. */
export function ContactDragHandle(props: HTMLAttributes<HTMLSpanElement>) {
  return (
    <Tooltip title="Drag onto another person, or onto “The company itself”">
      <span
        {...props}
        style={{
          cursor: 'grab',
          color: 'rgba(0,0,0,.35)',
          padding: '2px 4px',
          // Without this a touch drag scrolls the drawer instead of lifting the row.
          touchAction: 'none',
          ...props.style,
        }}
      >
        <HolderOutlined />
      </span>
    </Tooltip>
  );
}
