import { useEffect, useState } from 'react';
import { Alert, Modal, Select, Space, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { vesselsApi } from '../../api/vessels';
import { useVesselMutations } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import type { VesselResponse } from '../../api/types';

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
  const { setOwner } = useVesselMutations();

  const { data, isFetching } = useQuery({
    queryKey: ['vessel-picker', term],
    queryFn: () => vesselsApi.search({ name: term || undefined, size: 20, sort: 'name,asc' }),
    enabled: open,
  });

  useEffect(() => {
    if (open) {
      setTerm('');
      setPicked(undefined);
    }
  }, [open]);

  // Vessels this company already owns are in the tab's list already.
  const candidates = (data?.content ?? []).filter((v) => v.ownerId !== companyId);

  return (
    <Modal
      open={open}
      title="Link an existing vessel"
      okText="Link"
      okButtonProps={{ disabled: !picked }}
      confirmLoading={setOwner.isPending}
      onCancel={onClose}
      onOk={() => picked && setOwner.mutate({ vessel: picked, ownerId: companyId }, { onSuccess: onClose })}
      destroyOnClose
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          Search the fleet by name, then link it to <strong>{companyName}</strong>.
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
        {picked?.ownerId != null && (
          <Alert
            type="warning"
            showIcon
            message={`Currently owned by ${picked.ownerName ?? 'another company'}`}
            description={`Linking moves it to ${companyName}. A vessel can only have one owner.`}
          />
        )}
      </Space>
    </Modal>
  );
}

/** Reassign one vessel to a different company — or clear the owner entirely. */
export function MoveVesselModal({
  vessel,
  onClose,
}: {
  vessel: VesselResponse | null;
  onClose: () => void;
}) {
  const [ownerId, setOwnerId] = useState<number>();
  const { setOwner } = useVesselMutations();

  useEffect(() => {
    if (vessel) setOwnerId(vessel.ownerId);
  }, [vessel]);

  return (
    <Modal
      open={vessel != null}
      title={vessel ? `Move ${vessel.name}` : 'Move vessel'}
      okText="Save"
      confirmLoading={setOwner.isPending}
      onCancel={onClose}
      onOk={() => vessel && setOwner.mutate({ vessel, ownerId }, { onSuccess: onClose })}
      destroyOnClose
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text type="secondary">
          Pick the owning company, or clear the field to leave the vessel unassigned.
        </Typography.Text>
        <CompanySelect
          allowClear
          value={ownerId}
          onChange={setOwnerId}
          placeholder="Search owner company…"
        />
      </Space>
    </Modal>
  );
}
