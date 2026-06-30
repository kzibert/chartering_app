import { useEffect } from 'react';
import { Col, Form, Input, InputNumber, Modal, Row, Select } from 'antd';
import { useVesselMutations, useVesselTypes, useFlags } from '../../api/hooks';
import CompanySelect from '../../components/CompanySelect';
import type { VesselRequest, VesselResponse } from '../../api/types';

interface Props {
  open: boolean;
  editing?: VesselResponse | null;
  onClose: () => void;
}

export default function VesselForm({ open, editing, onClose }: Props) {
  const [form] = Form.useForm<VesselRequest>();
  const { create, update } = useVesselMutations();
  const { data: types } = useVesselTypes();
  const { data: flags } = useFlags();

  useEffect(() => {
    if (open) {
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
          notes: undefined,
        });
      } else {
        form.resetFields();
      }
    }
  }, [open, editing, form]);

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
    </Modal>
  );
}
