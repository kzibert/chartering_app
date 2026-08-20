import { App, Button, Tooltip } from 'antd';
import { StopOutlined } from '@ant-design/icons';
import { useContactMutations } from '../api/hooks';
import type { ContactResponse } from '../api/types';

/**
 * Flag an address as one that must never receive a circular.
 *
 * For an address that works perfectly well but should stay out of bulk mail — an accounts@
 * or ops@ inbox, or a broker who asked to come off the circular. It is left out of bulk
 * collection and dropped again at send time, so an address already sitting in a saved list
 * still cannot be mailed.
 *
 * Deliberately not the same control as "not working". A dead address can receive nothing at
 * all; this one is still the right address to write to by hand. Using the dead flag for this
 * would hide the address everywhere and leave nobody able to tell a bounced mailbox from a
 * deliberate exclusion.
 *
 * Phones are never circulated, so nothing is rendered for them.
 */
export default function NoCircToggleButton({ ct }: { ct: ContactResponse }) {
  const { setNoCirc } = useContactMutations();
  const { message } = App.useApp();

  if (ct.contactKind !== 'email') return null;

  const who = ct.personName ?? ct.companyName ?? 'this contact';
  const title = ct.noCirc
    ? `Never circulated to — click to allow circulations to ${who} again`
    : `Never circulate to this address. It stays usable for writing to ${who} by hand.`;

  return (
    <Tooltip title={title}>
      <Button
        type="text"
        size="small"
        aria-label={title}
        loading={setNoCirc.isPending}
        icon={<StopOutlined style={ct.noCirc ? { color: '#cf1322' } : undefined} />}
        onClick={() =>
          setNoCirc.mutate(
            { id: ct.id, noCirc: !ct.noCirc },
            {
              onSuccess: () =>
                message.success(
                  ct.noCirc
                    ? `${ct.contactValue} can be circulated to again`
                    : `${ct.contactValue} will never be included in circulations`,
                ),
            },
          )
        }
      >
        no circ
      </Button>
    </Tooltip>
  );
}
