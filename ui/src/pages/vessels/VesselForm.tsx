import { useEffect, useState } from 'react';
import { Col, Form, Input, InputNumber, Modal, Row, Select, Typography } from 'antd';
import { useVesselMutations, useVesselTypes, useFlags } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import RecordActions from '../../components/RecordActions';
import ExNamesEditor from './ExNamesEditor';
import type { VesselRequest, VesselResponse } from '../../api/types';

/**
 * Two options, never three. "Not on file" is the absence of a choice, reached by clearing
 * the box — an option carrying `undefined` would render as a selectable blank row that antd
 * treats as picking nothing, which is the same state behind a control implying otherwise.
 */
const FITTED_OPTIONS = (yes: string, no: string) => [
  { value: true, label: yes },
  { value: false, label: no },
];

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
          geared: editing.geared,
          gearDescription: editing.gearDescription,
          holds: editing.holds,
          hatches: editing.hatches,
          grainFitted: editing.grainFitted,
          timberFitted: editing.timberFitted,
          imoFitted: editing.imoFitted,
          iceClass: editing.iceClass,
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
        {/* The details a charterer asks about first, and the ones every position list
            carries: "1 HO/2 HA", "imo-timber-grain ftd", "2x30t cranes". Each is
            three-valued — the box is cleared to say "still not on file", which is the
            honest state for most of this fleet and is not the same as a no. */}
        <Typography.Text type="secondary">Gear, holds and fittings</Typography.Text>
        <Row gutter={12} style={{ marginTop: 8 }}>
          <Col xs={12} md={6}>
            <Form.Item name="geared" label="Gear">
              <Select allowClear placeholder="Not on file" options={FITTED_OPTIONS('Geared', 'Gearless')} />
            </Form.Item>
          </Col>
          <Col xs={24} md={10}>
            <Form.Item
              name="gearDescription"
              label="Gear detail"
              tooltip="As the list wrote it. A column of enumerated crane types would discard most of what a charterer actually reads."
            >
              <Input placeholder="2x30t cranes, grabs 2x6cbm…" />
            </Form.Item>
          </Col>
          <Col xs={12} md={4}>
            <Form.Item name="holds" label="Holds">
              <InputNumber style={{ width: '100%' }} min={0} max={20} />
            </Form.Item>
          </Col>
          <Col xs={12} md={4}>
            <Form.Item name="hatches" label="Hatches">
              <InputNumber style={{ width: '100%' }} min={0} max={20} />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={12}>
          <Col xs={12} md={6}>
            <Form.Item name="grainFitted" label="Grain fitted">
              <Select allowClear placeholder="Not on file" options={FITTED_OPTIONS('Fitted', 'Not fitted')} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="timberFitted" label="Timber fitted">
              <Select allowClear placeholder="Not on file" options={FITTED_OPTIONS('Fitted', 'Not fitted')} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item name="imoFitted" label="IMO fitted">
              <Select allowClear placeholder="Not on file" options={FITTED_OPTIONS('Fitted', 'Not fitted')} />
            </Form.Item>
          </Col>
          <Col xs={12} md={6}>
            <Form.Item
              name="iceClass"
              label="Ice class"
              tooltip="Free text: the class societies do not agree on one scale, and 1A, 1A Super and E3 all turn up."
            >
              <Input placeholder="1A, E3…" />
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

      {/* Former names, and the actions, both only for a vessel that exists: there is
          nothing to rename, confirm, ban or delete about one not yet saved. */}
      {record && <ExNamesEditor vesselId={record.id} exNames={record.exNames ?? []} />}

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
