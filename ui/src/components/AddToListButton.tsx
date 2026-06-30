import { App, Button, Tooltip } from 'antd';
import { PlusOutlined, CheckCircleTwoTone } from '@ant-design/icons';
import { useEmailList, contactToEntry } from '../emailList/store';
import type { ContactResponse } from '../api/types';

/**
 * Toggle an email contact in/out of the client-side email list. Renders nothing for
 * non-email contacts (phones), so it can be dropped into any contact row safely.
 */
export default function AddToListButton({ ct }: { ct: ContactResponse }) {
  const { has, add, remove } = useEmailList();
  const { message } = App.useApp();

  if (ct.contactKind !== 'email') return null;
  const inList = has(ct.id);

  return (
    <Tooltip title={inList ? 'In email list — click to remove' : 'Add email to list'}>
      <Button
        type="text"
        size="small"
        aria-label={inList ? 'Remove email from list' : 'Add email to list'}
        icon={inList ? <CheckCircleTwoTone twoToneColor="#52c41a" /> : <PlusOutlined />}
        onClick={() => {
          if (inList) {
            remove(ct.id);
            message.info(`Removed ${ct.contactValue}`);
          } else {
            add(contactToEntry(ct));
            message.success(`Added ${ct.contactValue}`);
          }
        }}
      />
    </Tooltip>
  );
}
