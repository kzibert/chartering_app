import { List, Space, Tag } from 'antd';
import CopyableValue from './CopyableValue';
import GreetingName from './GreetingName';
import AddToListButton from './AddToListButton';
import MainContactButton from './MainContactButton';
import WorkingToggleButton from './WorkingToggleButton';
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
        {ct.main && <Tag color="gold">main</Tag>}
        {!ct.working && <Tag color="red">not working</Tag>}
        {ct.confirmed && <Tag color="success">confirmed</Tag>}
        <MainContactButton ct={ct} />
        <WorkingToggleButton ct={ct} />
        <AddToListButton ct={ct} />
      </Space>
    </List.Item>
  );
}
