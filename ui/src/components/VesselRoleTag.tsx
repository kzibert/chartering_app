import { Tag, Tooltip } from 'antd';
import type { VesselCompanyRole } from '../api/types';

/** One label and colour per role, so the vessel and company sides never disagree. */
export const ROLE_LABEL: Record<VesselCompanyRole, string> = {
  owner: 'owner',
  exclusive_broker: 'exclusive broker',
  broker: 'broker',
};

const ROLE_COLOR: Record<VesselCompanyRole, string> = {
  owner: 'blue',
  exclusive_broker: 'purple',
  broker: 'default',
};

const ROLE_HINT: Record<VesselCompanyRole, string> = {
  owner: 'Owns the vessel. Circulars collect the owner’s contacts.',
  exclusive_broker: 'Sole broker for this vessel — only one company can hold this.',
  broker: 'Works this vessel as one of possibly several brokers.',
};

export const ROLE_OPTIONS = (Object.keys(ROLE_LABEL) as VesselCompanyRole[]).map((r) => ({
  value: r,
  label: ROLE_LABEL[r],
}));

export default function VesselRoleTag({ role }: { role: VesselCompanyRole }) {
  return (
    <Tooltip title={ROLE_HINT[role]}>
      <Tag color={ROLE_COLOR[role]}>{ROLE_LABEL[role]}</Tag>
    </Tooltip>
  );
}
