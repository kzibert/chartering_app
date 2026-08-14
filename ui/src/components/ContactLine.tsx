import { Button, List, Popconfirm, Space, Tag, Tooltip } from 'antd';
import { DeleteOutlined, EditOutlined } from '@ant-design/icons';
import CopyableValue from './CopyableValue';
import GreetingName from './GreetingName';
import AddToListButton from './AddToListButton';
import BanButton from './BanButton';
import MainContactButton from './MainContactButton';
import CircToggleButton from './CircToggleButton';
import WorkingToggleButton from './WorkingToggleButton';
import { useContactMutations } from '../api/hooks';
import type { ContactResponse } from '../api/types';

/**
 * One email/phone line with copy buttons. When `showGreeting` is set (default),
 * a copiable English greeting name is shown for person-linked contacts — handy
 * when the list isn't already grouped by person (company Contacts tab, vessel
 * owner contacts).
 */
export default function ContactLine({
  ct,
  showGreeting = true,
  editing = false,
  highlight,
  onEdit,
  onDelete,
}: {
  ct: ContactResponse;
  showGreeting?: boolean;
  /** Substring to mark in the value — the term this row was searched by. */
  highlight?: string;
  /**
   * Gates every control that writes to the contact — main, not-working, edit, delete —
   * so browsing a list stays read-only until the caller's Edit toggle is on. Add-to-list
   * is deliberately not gated: it only builds the local email list.
   */
  editing?: boolean;
  /** Supply to show an edit button. The caller owns the form, so one drawer renders one. */
  onEdit?: (ct: ContactResponse) => void;
  /** Supply to show a delete button (already behind a confirmation popup). */
  onDelete?: (ct: ContactResponse) => void;
}) {
  const { ban } = useContactMutations();
  return (
    <List.Item>
      <Space wrap size={4}>
        {showGreeting && ct.greetingName && (
          <GreetingName title={ct.title} name={ct.greetingName} type="secondary" />
        )}
        <Tag color={ct.contactKind === 'email' ? 'blue' : 'default'}>{ct.contactKind}</Tag>
        <CopyableValue value={ct.contactValue} highlight={highlight} />
        {ct.main && <Tag color="gold">main</Tag>}
        {ct.circ && (
          <Tooltip title="Used for circulations — collected instead of this person's other addresses">
            <Tag color="blue">circ</Tag>
          </Tooltip>
        )}
        {!ct.working && <Tag color="red">not working</Tag>}
        {ct.confirmed && <Tag color="success">confirmed</Tag>}
        {ct.banned && <Tag color="red">banned</Tag>}
        {editing && <MainContactButton ct={ct} />}
        {editing && <CircToggleButton ct={ct} />}
        {editing && <WorkingToggleButton ct={ct} />}
        <AddToListButton ct={ct} />
        {editing && onEdit && (
          <Tooltip title="Edit contact">
            <Button
              type="text"
              size="small"
              aria-label="Edit contact"
              icon={<EditOutlined />}
              onClick={() => onEdit(ct)}
            />
          </Tooltip>
        )}
        {editing && (
          <BanButton
            banned={ct.banned}
            loading={ban.isPending}
            size="small"
            onToggle={(b) => ban.mutate({ id: ct.id, banned: b })}
          />
        )}
        {editing && onDelete && (
          // No Tooltip here: nesting one inside Popconfirm makes both popups fight
          // over the same trigger. The confirm title says what the button does.
          <Popconfirm title="Delete this contact?" onConfirm={() => onDelete(ct)}>
            <Button
              type="text"
              size="small"
              danger
              aria-label="Delete contact"
              icon={<DeleteOutlined />}
            />
          </Popconfirm>
        )}
      </Space>
    </List.Item>
  );
}
