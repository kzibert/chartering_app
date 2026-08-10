import { App, Button, Tooltip } from 'antd';
import { CloseCircleOutlined, UndoOutlined } from '@ant-design/icons';
import { useContactMutations } from '../api/hooks';
import type { ContactResponse } from '../api/types';

/**
 * Flag an address/number as dead, or revive it. A non-working email is skipped by the
 * bulk email-list actions and dropped again when a campaign starts, so flagging one is
 * enough to keep it out of circulations even if it already sits in someone's email list.
 */
export default function WorkingToggleButton({ ct }: { ct: ContactResponse }) {
  const { setWorking } = useContactMutations();
  const { message } = App.useApp();

  const title = ct.working
    ? `Mark this ${ct.contactKind} as not working — it will be excluded from circulations`
    : `Marked not working — click to restore`;

  return (
    <Tooltip title={title}>
      <Button
        type="text"
        size="small"
        danger={ct.working}
        aria-label={title}
        loading={setWorking.isPending}
        icon={ct.working ? <CloseCircleOutlined /> : <UndoOutlined />}
        onClick={() =>
          setWorking.mutate(
            { id: ct.id, working: !ct.working },
            {
              onSuccess: () =>
                message.success(
                  ct.working
                    ? `${ct.contactValue} marked as not working`
                    : `${ct.contactValue} restored as working`,
                ),
            },
          )
        }
      />
    </Tooltip>
  );
}
