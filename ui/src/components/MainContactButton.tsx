import { App, Button, Tooltip } from 'antd';
import { StarFilled, StarOutlined } from '@ant-design/icons';
import { useContactMutations } from '../api/hooks';
import type { ContactResponse } from '../api/types';

/**
 * Toggle a contact as its company's main email/phone. Promoting one demotes whatever
 * held that slot before (one main email + one main phone per company), so the star is
 * a radio choice within a company, not a free-standing flag.
 *
 * Renders nothing for contacts attached to no company — there is no slot to claim.
 */
export default function MainContactButton({ ct }: { ct: ContactResponse }) {
  const { setMain } = useContactMutations();
  const { message } = App.useApp();

  if (ct.companyId == null) return null;

  const company = ct.companyName ?? 'this company';
  const title = ct.main
    ? `Main ${ct.contactKind} for ${company} — click to unset`
    : `Set as main ${ct.contactKind} for ${company}`;

  return (
    <Tooltip title={title}>
      <Button
        type="text"
        size="small"
        aria-label={title}
        loading={setMain.isPending}
        icon={ct.main ? <StarFilled style={{ color: '#faad14' }} /> : <StarOutlined />}
        onClick={() =>
          setMain.mutate(
            { id: ct.id, main: !ct.main },
            {
              onSuccess: () =>
                message.success(
                  ct.main
                    ? `${ct.contactValue} is no longer the main ${ct.contactKind}`
                    : `${ct.contactValue} is now the main ${ct.contactKind} for ${company}`,
                ),
            },
          )
        }
      >
        main
      </Button>
    </Tooltip>
  );
}
