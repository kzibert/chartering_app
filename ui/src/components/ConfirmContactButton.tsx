import { useState } from 'react';
import { App, Button, Tooltip } from 'antd';
import { CheckCircleOutlined, UndoOutlined } from '@ant-design/icons';
import ConfirmModal from './ConfirmModal';
import { useContactMutations } from '../api/hooks';
import type { ContactResponse } from '../api/types';

/**
 * Attest that an address or number is still current, or clear that attestation.
 *
 * Contacts have carried a confirm block — the flag, who set it and when, and their notes —
 * for as long as vessels and companies have, and the endpoint has always been there; there
 * was simply never a control for it, so the "confirmed" tag on a contact row could only
 * ever have arrived from an import. This is that control.
 *
 * It lives in the row's expanded strip with the other writes, so it is behind edit mode by
 * construction, the same way confirming a vessel or a company now is.
 */
export default function ConfirmContactButton({ ct }: { ct: ContactResponse }) {
  const { confirm } = useContactMutations();
  const { message } = App.useApp();
  const [open, setOpen] = useState(false);

  if (ct.confirmed) {
    // No modal on the way out: there is nothing to collect. The tooltip carries the cost
    // instead, because the who and the when go with the flag and the server keeps no
    // history of them.
    return (
      <Tooltip title={`Confirmed by ${ct.confirmedBy ?? 'unknown'} — clearing this discards that and the date`}>
        <Button
          type="text"
          size="small"
          aria-label="Clear the confirmation on this contact"
          loading={confirm.isPending}
          icon={<UndoOutlined />}
          onClick={() =>
            confirm.mutate(
              { id: ct.id, confirmed: false },
              { onSuccess: () => message.success(`${ct.contactValue} is no longer confirmed`) },
            )
          }
        >
          unconfirm
        </Button>
      </Tooltip>
    );
  }

  return (
    <>
      <Tooltip title={`Record that this ${ct.contactKind} was checked and is still current`}>
        <Button
          type="text"
          size="small"
          aria-label="Confirm this contact is up to date"
          loading={confirm.isPending}
          icon={<CheckCircleOutlined />}
          onClick={() => setOpen(true)}
        >
          confirm
        </Button>
      </Tooltip>
      <ConfirmModal
        open={open}
        title="Confirm this contact is up to date"
        loading={confirm.isPending}
        onCancel={() => setOpen(false)}
        onSubmit={(body) => {
          setOpen(false);
          confirm.mutate(
            { id: ct.id, confirmed: true, body },
            { onSuccess: () => message.success(`${ct.contactValue} confirmed`) },
          );
        }}
      />
    </>
  );
}
