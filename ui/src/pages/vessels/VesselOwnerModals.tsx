import { useEffect, useState } from 'react';
import { Alert, Modal, Select, Space, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { vesselsApi } from '../../api/vessels';
import { useVesselMutations } from '../../api/hooks';
import { ROLE_LABEL, ROLE_OPTIONS } from '../../components/VesselRoleTag';
import type { VesselCompanyRole, VesselResponse } from '../../api/types';

const optionLabel = (v: VesselResponse) => {
  const bits = [v.imoNumber && `IMO ${v.imoNumber}`, v.yearBuilt, v.ownerName ?? 'no owner'].filter(
    Boolean,
  );
  return `${v.name} — ${bits.join(' · ')}`;
};

/**
 * Attach a vessel that already exists in the fleet database to this company.
 * A vessel has exactly one owner, so picking one that is owned elsewhere moves it —
 * the modal says so before you confirm.
 */
export function LinkVesselModal({
  open,
  companyId,
  companyName,
  onClose,
}: {
  open: boolean;
  companyId: number;
  companyName: string;
  onClose: () => void;
}) {
  const [term, setTerm] = useState('');
  const [picked, setPicked] = useState<VesselResponse>();
  const [role, setRole] = useState<VesselCompanyRole>('owner');
  const { setLink } = useVesselMutations();

  const { data, isFetching } = useQuery({
    queryKey: ['vessel-picker', term],
    queryFn: () => vesselsApi.search({ name: term || undefined, size: 20, sort: 'name,asc' }),
    enabled: open,
  });

  useEffect(() => {
    if (open) {
      setTerm('');
      setPicked(undefined);
      setRole('owner');
    }
  }, [open]);

  // Vessels this company already owns are in the tab's list already. Ones it merely
  // brokers are not filtered out here — re-attaching just changes the role.
  const candidates = (data?.content ?? []).filter((v) => v.ownerId !== companyId);

  return (
    <Modal
      open={open}
      title="Link an existing vessel"
      okText="Link"
      okButtonProps={{ disabled: !picked }}
      confirmLoading={setLink.isPending}
      onCancel={onClose}
      onOk={() =>
        picked &&
        setLink.mutate({ vesselId: picked.id, companyId, role }, { onSuccess: onClose })
      }
      destroyOnClose
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          Search the fleet by name, then attach it to <strong>{companyName}</strong>.
        </Typography.Text>
        <Select
          showSearch
          autoFocus
          placeholder="Search vessel by name…"
          style={{ width: '100%' }}
          value={picked?.id}
          filterOption={false}
          onSearch={setTerm}
          loading={isFetching}
          notFoundContent={isFetching ? 'Searching…' : 'No vessels found'}
          onChange={(id: number) => setPicked(candidates.find((v) => v.id === id))}
          options={candidates.map((v) => ({ value: v.id, label: optionLabel(v) }))}
        />
        <Select style={{ width: '100%' }} value={role} options={ROLE_OPTIONS} onChange={setRole} />
        {role === 'owner' && picked?.ownerId != null && picked.ownerId !== companyId && (
          <Alert
            type="warning"
            showIcon
            message={`Currently owned by ${picked.ownerName ?? 'another company'}`}
            description={`Attaching as ${ROLE_LABEL.owner} moves it to ${companyName}. A vessel has one owner.`}
          />
        )}
      </Space>
    </Modal>
  );
}
