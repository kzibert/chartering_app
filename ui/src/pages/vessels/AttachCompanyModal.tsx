import { useEffect, useState } from 'react';
import { Alert, Modal, Select, Space, Typography } from 'antd';
import { useVesselMutations } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import { ROLE_LABEL, ROLE_OPTIONS } from '../../components/VesselRoleTag';
import type { VesselCompanyLinkResponse, VesselCompanyRole } from '../../api/types';

/** Attach a company to a vessel as owner, exclusive broker or plain broker. */
export default function AttachCompanyModal({
  open,
  vesselId,
  vesselName,
  existing,
  onClose,
}: {
  open: boolean;
  vesselId: number;
  vesselName: string;
  /** Already-linked companies — used to warn about the replacements a save would cause. */
  existing: VesselCompanyLinkResponse[];
  onClose: () => void;
}) {
  const { setLink } = useVesselMutations();
  const [companyId, setCompanyId] = useState<number>();
  const [role, setRole] = useState<VesselCompanyRole>('broker');

  useEffect(() => {
    if (open) {
      setCompanyId(undefined);
      setRole('broker');
    }
  }, [open]);

  const alreadyLinked = existing.find((l) => l.companyId === companyId);
  const currentOwner = existing.find((l) => l.role === 'owner');
  const currentExclusive = existing.find((l) => l.role === 'exclusive_broker');

  // Spell out the knock-on effects before saving: these rules move other companies.
  const consequences: string[] = [];
  if (alreadyLinked && alreadyLinked.role !== role) {
    consequences.push(
      `${alreadyLinked.companyName} is currently ${ROLE_LABEL[alreadyLinked.role]} here — that becomes ${ROLE_LABEL[role]}.`,
    );
  }
  if (role === 'owner' && currentOwner && currentOwner.companyId !== companyId) {
    consequences.push(`${currentOwner.companyName} stops being the owner — a vessel has one.`);
  }
  if (role === 'exclusive_broker' && currentExclusive && currentExclusive.companyId !== companyId) {
    consequences.push(`${currentExclusive.companyName} drops from exclusive broker to broker.`);
  }

  return (
    <Modal
      open={open}
      title={`Attach a company to ${vesselName}`}
      okText="Attach"
      okButtonProps={{ disabled: companyId == null }}
      confirmLoading={setLink.isPending}
      onCancel={onClose}
      onOk={() =>
        companyId != null &&
        setLink.mutate({ vesselId, companyId, role }, { onSuccess: onClose })
      }
      destroyOnClose
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <div>
          <Typography.Text type="secondary">Company</Typography.Text>
          <CompanySelect allowClear value={companyId} onChange={setCompanyId} />
        </div>
        <div>
          <Typography.Text type="secondary">Acting as</Typography.Text>
          <Select
            style={{ width: '100%' }}
            value={role}
            options={ROLE_OPTIONS}
            onChange={setRole}
          />
        </div>
        {consequences.length > 0 && (
          <Alert
            type="warning"
            showIcon
            message="This also changes:"
            description={
              <ul style={{ margin: 0, paddingInlineStart: 18 }}>
                {consequences.map((c) => <li key={c}>{c}</li>)}
              </ul>
            }
          />
        )}
      </Space>
    </Modal>
  );
}
