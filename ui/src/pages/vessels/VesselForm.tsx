import { useEffect, useState } from 'react';
import { Col, Form, Input, InputNumber, Modal, Row, Select } from 'antd';
import { useVesselMutations, useVesselTypes, useFlags } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import RecordActions from '../../components/RecordActions';
import type { VesselRequest, VesselResponse } from '../../api/types';

interface Props {
  open: boolean;
  editing?: VesselResponse | null;
  /**
   * Prefilled fields for a new vessel — e.g. the owner when adding from a company
   * drawer. Ignored when editing. Keep the object referentially stable (useMemo).
   */
  defaults?: Partial<VesselRequest>;
  onClose: () => void;
  /**
   * The vessel was deleted from in here. The form closes itself either way; this is for a
   * caller that also has a drawer open on the same vessel, which would otherwise be left
   * showing a record the server no longer has.
   */
  onDeleted?: () => void;
}

export default function VesselForm({ open, editing, defaults, onClose, onDeleted }: Props) {
  const [form] = Form.useForm<VesselRequest>();
  const { create, update, remove, confirm, ban } = useVesselMutations();
  const { data: types } = useVesselTypes();
  const { data: flags } = useFlags();

  /**
   * The record as the server last described it.
   *
   * `editing` is a snapshot taken from the row that was clicked, and confirming or banning
   * does not update it — the page's copy stays as it was until the list refetches, so the
   * tag would still read "Needs confirm" a moment after you confirmed. Both endpoints hand
   * back the saved record, so the answer is simply to keep what they returned.
   */
  const [record, setRecord] = useState<VesselResponse | null>(editing ?? null);
  useEffect(() => {
    if (open) setRecord(editing ?? null);
  }, [open, editing]);

  useEffect(() => {
    if (open) {
      form.resetFields();
      if (editing) {
        form.setFieldsValue({
          name: editing.name,
          imoNumber: editing.imoNumber,
          deadweightTonnage: editing.deadweightTonnage,
          deadweightCargoCapacity: editing.deadweightCargoCapacity,
          grainCapacityM3: editing.grainCapacityM3,
          baleCapacityM3: editing.baleCapacityM3,
          maximumDraft: editing.maximumDraft,
          yearBuilt: editing.yearBuilt,
          vesselType: editing.vesselType,
          flag: editing.flag,
          ownerId: editing.ownerId,
          notes: editing.notes,
        });
      } else if (defaults) {
        form.setFieldsValue(defaults);
      }
    }
  }, [open, editing, defaults, form]);

  const submit = (values: VesselRequest) => {
    const done = { onSuccess: onClose };
    if (editing) update.mutate({ id: editing.id, body: values }, done);
    else create.mutate(values, done);
  };

  return (
    <Modal
      open={open}
      title={editing ? `Edit vessel — ${editing.name}` : 'New vessel'}
      okText="Save"
      width={680}
      confirmLoading={create.isPending || update.isPending}
      onCancel={onClose}
      onOk={() => form.submit()}
      destroyOnClose
    >
      <Form form={form} layout="vertical" onFinish={submit}>
        <Row gutter={12}>
          <Col span={16}>
            <Form.Item name="name" label="Name" rules={[{ required: true, message: 'name is required' }]}>
              <Input />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="imoNumber" label="IMO">
              <Input />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={12}>
          <Col span={8}>
            <Form.Item name="deadweightTonnage" label="DWT">
              <InputNumber style={{ width: '100%' }} min={0} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="deadweightCargoCapacity" label="DWCC">
              <InputNumber style={{ width: '100%' }} min={0} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="yearBuilt" label="Year built">
              <InputNumber style={{ width: '100%' }} min={1900} max={2100} />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={12}>
          <Col span={8}>
            <Form.Item name="grainCapacityM3" label="Grain (m³)">
              <InputNumber style={{ width: '100%' }} min={0} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="baleCapacityM3" label="Bale (m³)">
              <InputNumber style={{ width: '100%' }} min={0} />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="maximumDraft" label="Max draft">
              <InputNumber style={{ width: '100%' }} min={0} />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={12}>
          <Col span={12}>
            <Form.Item name="vesselType" label="Type">
              <Select allowClear options={(types ?? []).map((t) => ({ value: t, label: t }))} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="flag" label="Flag">
              <Select allowClear options={(flags ?? []).map((f) => ({ value: f, label: f }))} />
            </Form.Item>
          </Col>
        </Row>
        <Form.Item name="ownerId" label="Owner">
          <CompanySelect allowClear placeholder="Search owner company…" />
        </Form.Item>
        <Form.Item name="notes" label="Notes">
          <Input.TextArea rows={2} />
        </Form.Item>
      </Form>

      {/* Only for a vessel that exists: there is nothing to confirm, ban or delete about
          one that has not been saved yet. */}
      {record && (
        <RecordActions
          entity="vessel"
          name={record.name}
          confirmed={record.confirmed}
          confirmedAt={record.confirmedAt}
          confirmedBy={record.confirmedBy}
          confirmLoading={confirm.isPending}
          onConfirm={(body) =>
            confirm.mutate(
              { id: record.id, confirmed: true, body },
              { onSuccess: setRecord },
            )
          }
          onUnconfirm={() =>
            confirm.mutate({ id: record.id, confirmed: false }, { onSuccess: setRecord })
          }
          banned={record.banned}
          banLoading={ban.isPending}
          onToggleBan={(banned) =>
            ban.mutate({ id: record.id, banned }, { onSuccess: setRecord })
          }
          deleteLoading={remove.isPending}
          // What actually cascades, per the FK constraints in V1__baseline_schema.sql:
          // vessel_company_links goes, the companies at the other end of it do not.
          deleteWarning="The vessel is removed from the database. Companies linked to it are kept — only the link between them goes."
          onDelete={() =>
            remove.mutate(record.id, {
              onSuccess: () => {
                onClose();
                onDeleted?.();
              },
            })
          }
        />
      )}
    </Modal>
  );
}
