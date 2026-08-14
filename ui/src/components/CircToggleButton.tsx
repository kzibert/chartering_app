import { App, Button, Tooltip } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { useContactMutations } from '../api/hooks';
import type { ContactResponse } from '../api/types';

/**
 * Flag an address as one to use when circulating.
 *
 * <p>Unlike the main-contact star this is not a radio choice: any number of a person's or
 * company's addresses may carry it, because "who gets the circular" and "one address to
 * reach them on" are different questions. Flagging one narrows bulk collection for that
 * person only — colleagues keep whatever their own flags say.
 *
 * <p>Phones are not circulated, so nothing is rendered for them.
 */
export default function CircToggleButton({ ct }: { ct: ContactResponse }) {
  const { setCirc } = useContactMutations();
  const { message } = App.useApp();

  if (ct.contactKind !== 'email') return null;

  const who = ct.personName ?? ct.companyName ?? 'this contact';
  const title = ct.circ
    ? `Used for circulations to ${who} — click to unset`
    : `Use this address for circulations to ${who}`;

  return (
    <Tooltip title={title}>
      <Button
        type="text"
        size="small"
        aria-label={title}
        loading={setCirc.isPending}
        icon={<SendOutlined style={ct.circ ? { color: '#1677ff' } : undefined} />}
        onClick={() =>
          setCirc.mutate(
            { id: ct.id, circ: !ct.circ },
            {
              onSuccess: () =>
                message.success(
                  ct.circ
                    ? `${ct.contactValue} is no longer flagged for circulations`
                    : `${ct.contactValue} will be used for circulations to ${who}`,
                ),
            },
          )
        }
      />
    </Tooltip>
  );
}
