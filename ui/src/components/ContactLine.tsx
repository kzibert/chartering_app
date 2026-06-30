import { List, Space, Tag } from 'antd';
import CopyableValue from './CopyableValue';
import GreetingName from './GreetingName';
import AddToListButton from './AddToListButton';
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
}: {
  ct: ContactResponse;
  showGreeting?: boolean;
}) {
  return (
    <List.Item>
      <Space wrap size={4}>
        {showGreeting && ct.greetingName && (
          <GreetingName title={ct.title} name={ct.greetingName} type="secondary" />
        )}
        <Tag color={ct.contactKind === 'email' ? 'blue' : 'default'}>{ct.contactKind}</Tag>
        <CopyableValue value={ct.contactValue} />
        {ct.confirmed && <Tag color="success">confirmed</Tag>}
        <AddToListButton ct={ct} />
      </Space>
    </List.Item>
  );
}
