import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, Col, DatePicker, Form, Input, InputNumber, Modal, Row, Select, Typography } from 'antd';
import dayjs from 'dayjs';
import { useCargoMutations } from '../../api/hooks';
import FormWithReference from '../../components/FormWithReference';
import CompanySelect from '../../components/CompanySelect';
import PortSelect from '../../components/PortSelect';
import TradeAreaSelect from '../../components/TradeAreaSelect';
import RecordActions from '../../components/RecordActions';
import { toCargoRequest } from '../../api/cargoes';
import { CARGO_STATUS_OPTIONS } from './status';
import type { CargoRequest, CargoResponse } from '../../api/types';

interface Props {
  open: boolean;
  editing?: CargoResponse | null;
  /** Prefilled fields for a new cargo — the mail-derived case. Ignored when editing. */
  defaults?: Partial<CargoRequest>;
  /**
   * Source text to keep on screen while the form is filled in — the email this requirement
   * arrived in. See {@link FormWithReference}; absent, the dialog is unchanged.
   */
  reference?: ReactNode;
  onClose: () => void;
  /** The cargo was deleted from in here, for a caller with a drawer open on the same row. */
  onDeleted?: () => void;
}

/**
 * The requirements are three-valued, and the third value is the absence of a choice.
 *
 * Only two options are listed; "not said" is what clearing the box means, which is why every
 * one of these Selects is allowClear with "Not said" as its placeholder. A third option
 * carrying `undefined` would render as a selectable blank row and antd would treat picking
 * it as picking nothing — the same state, reached by a control that implies otherwise.
 */
const REQUIREMENT_OPTIONS = [
  { value: true, label: 'Required' },
  { value: false, label: 'Not required' },
];

/**
 * The cargo form.
 *
 * **Only the commodity is required, and the form says so rather than merely permitting it.**
 * A real first enquiry is "25,000 MT Wheat +/- 10%, Chornomorsk to Spain Med, laycan please
 * advise" and a form that demanded a laycan would send the broker back to a notebook. Every
 * other field is what this particular email happened to give.
 *
 * The dates and the free text sit beside each other throughout — laycan pickers next to a
 * laycan text box, port pickers next to a port text box — because both are true at once.
 * The pickers are what Match can read; the text is what the charterer actually wrote, and
 * losing it to make the record tidy would lose the only version anyone can check against.
 */
export default function CargoForm({
  open,
  editing,
  defaults,
  reference,
  onClose,
  onDeleted,
}: Props) {
  const [form] = Form.useForm();
  const { create, update, remove } = useCargoMutations();
  const [record, setRecord] = useState<CargoResponse | null>(editing ?? null);

  useEffect(() => {
    if (open) setRecord(editing ?? null);
  }, [open, editing]);

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    if (editing) {
      const body = toCargoRequest(editing);
      form.setFieldsValue({
        ...body,
        laycanFrom: editing.laycanFrom ? dayjs(editing.laycanFrom) : undefined,
        laycanTo: editing.laycanTo ? dayjs(editing.laycanTo) : undefined,
      });
    } else {
      // Defaults arrive in the shape the API takes — ISO days — and the pickers want dayjs.
      form.setFieldsValue({
        status: 'OPEN',
        quantityUnit: 'MT',
        ...defaults,
        laycanFrom: defaults?.laycanFrom ? dayjs(defaults.laycanFrom) : undefined,
        laycanTo: defaults?.laycanTo ? dayjs(defaults.laycanTo) : undefined,
      });
    }
  }, [open, editing, defaults, form]);

  const submit = (values: Record<string, unknown>) => {
    // The two date pickers hand back dayjs objects and the API wants plain ISO days, so the
    // form's values are not a CargoRequest until they are rewritten - which is why this
    // takes the loose shape and narrows on the way out rather than typing the Form.
    const body: CargoRequest = {
      ...(values as unknown as CargoRequest),
      laycanFrom: values.laycanFrom ? (values.laycanFrom as dayjs.Dayjs).format('YYYY-MM-DD') : undefined,
      laycanTo: values.laycanTo ? (values.laycanTo as dayjs.Dayjs).format('YYYY-MM-DD') : undefined,
      // Neither of these is a field anybody types, so neither reaches `values` — antd hands
      // back the registered fields and nothing else. They come from the defaults the caller
      // opened the form with, and only on a create: the API keeps an existing link when the
      // id is null, so how a cargo reached the desk survives every later edit of it.
      sourceMailMessageId: editing ? undefined : defaults?.sourceMailMessageId,
      receivedAt: editing ? undefined : defaults?.receivedAt,
    };
    const done = { onSuccess: onClose };
    if (editing) update.mutate({ id: editing.id, body }, done);
    else create.mutate(body, done);
  };

  return (
    <Modal
      open={open}
      title={editing ? `Edit cargo — ${editing.commodity}` : 'New cargo'}
      okText="Save"
      width={reference ? 1240 : 860}
      confirmLoading={create.isPending || update.isPending}
      onCancel={onClose}
      onOk={() => form.submit()}
      destroyOnClose
    >
      <FormWithReference reference={reference}>
        <Form form={form} layout="vertical" onFinish={submit}>
          <Row gutter={12}>
            <Col xs={24} md={10}>
              <Form.Item
                name="commodity"
                label="Commodity"
                rules={[{ required: true, message: 'commodity is required' }]}
              >
                <Input placeholder="Wheat, HBI, steel coils…" />
              </Form.Item>
            </Col>
            <Col xs={12} md={7}>
              <Form.Item name="status" label="Status">
                <Select options={CARGO_STATUS_OPTIONS} />
              </Form.Item>
            </Col>
            <Col xs={12} md={7}>
              <Form.Item
                name="stowageFactor"
                label="Stowage factor"
                tooltip="Cubic feet per tonne. What decides whether the cargo cubes out before it weighs out — and the only way a quantity can be tested against a grain capacity rather than only against deadweight."
              >
                <InputNumber style={{ width: '100%' }} min={0} placeholder="cbft/mt" />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Text type="secondary">Quantity</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={12} md={5}>
              <Form.Item name="quantity" label="Quantity">
                <InputNumber style={{ width: '100%' }} min={0} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="quantityUnit" label="Unit">
                <Input placeholder="MT" />
              </Form.Item>
            </Col>
            <Col xs={24} md={5}>
              <Form.Item
                name="quantityTolerance"
                label="Tolerance"
                tooltip="As the email wrote it. A plain percentage is read into the min/max below; MOLOO and the like are kept as written and left for you to turn into a range, because guessing a percentage nobody stated would quietly exclude ships that fit."
              >
                <Input placeholder="+/- 10%, MOLOO…" />
              </Form.Item>
            </Col>
            <Col xs={12} md={5}>
              <Form.Item name="quantityMin" label="Min (matching)">
                <InputNumber style={{ width: '100%' }} min={0} placeholder="derived" />
              </Form.Item>
            </Col>
            <Col xs={12} md={5}>
              <Form.Item name="quantityMax" label="Max (matching)">
                <InputNumber style={{ width: '100%' }} min={0} placeholder="derived" />
              </Form.Item>
            </Col>
          </Row>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 16 }}
            message="Leave min and max empty and they are worked out from the quantity and a percentage tolerance. Fill them and yours are kept — the arithmetic never overrides a range you typed."
          />

          <Typography.Text type="secondary">Load</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={24} md={8}>
              <Form.Item name="loadPortId" label="Load port">
                <PortSelect />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="loadPortText"
                label="Load, as written"
                tooltip="What the email said. Kept alongside the port so a range the ports table has no row for is not lost while somebody decides whether to add it."
              >
                <Input placeholder="Chornomorsk, Ukraine" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="loadAreaId"
                label="Load area"
                tooltip="Only needed when no port is named. With a port chosen, the port's own area is what matching uses."
              >
                <TradeAreaSelect />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Text type="secondary">Discharge</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={24} md={8}>
              <Form.Item name="dischargePortId" label="Discharge port">
                <PortSelect />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="dischargePortText" label="Discharge, as written">
                <Input placeholder="Spain Mediterranean" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="dischargeAreaId" label="Discharge area">
                <TradeAreaSelect />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Text type="secondary">Laycan</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={12} md={6}>
              <Form.Item name="laycanFrom" label="From">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="laycanTo" label="To">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="laycanText"
                label="Laycan, as written"
                tooltip='"Please advise suitable open tonnage" is a real laycan — it is what the charterer said — and no date at all as far as matching is concerned. Both are worth keeping.'
              >
                <Input placeholder="prompt, end Sept, please advise…" />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Text type="secondary">What the cargo needs of the ship</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={12} md={4}>
              <Form.Item name="minDwt" label="DWT min">
                <InputNumber style={{ width: '100%' }} min={0} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="maxDwt" label="DWT max">
                <InputNumber style={{ width: '100%' }} min={0} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="maxDraft" label="Max draft" tooltip="Deepest draft the berths can take.">
                <InputNumber style={{ width: '100%' }} min={0} step={0.1} />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="maxAgeYears" label="Max age">
                <InputNumber style={{ width: '100%' }} min={0} placeholder="years" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="requiresGeared" label="Gear">
                <Select allowClear options={REQUIREMENT_OPTIONS} placeholder="Not said" />
              </Form.Item>
            </Col>
            <Col xs={12} md={4}>
              <Form.Item name="requiresGrainFitted" label="Grain fitted">
                <Select allowClear options={REQUIREMENT_OPTIONS} placeholder="Not said" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={4}>
              <Form.Item name="requiresImoFitted" label="IMO fitted">
                <Select allowClear options={REQUIREMENT_OPTIONS} placeholder="Not said" />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Text type="secondary">Commercials and counterparties</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={24} md={8}>
              <Form.Item name="freightIdea" label="Freight idea">
                <Input placeholder="USD 25 pmt, lumpsum 120k, market related…" />
              </Form.Item>
            </Col>
            <Col xs={12} md={8}>
              <Form.Item name="commission" label="Commission">
                <Input placeholder="3.75% ttl" />
              </Form.Item>
            </Col>
            <Col xs={12} md={8}>
              <Form.Item name="terms" label="Terms">
                <Input placeholder="1/1, sscagsd, fio…" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={12} md={8}>
              <Form.Item name="loadRate" label="Load rate">
                <Input placeholder="3,000 mt pwwd shinc" />
              </Form.Item>
            </Col>
            <Col xs={12} md={8}>
              <Form.Item name="dischargeRate" label="Discharge rate">
                <Input placeholder="2,000 mt pwwd shinc" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col xs={24} md={8}>
              <Form.Item
                name="chartererCompanyId"
                label="Charterer"
                tooltip="Often not known at first — the enquiry arrives through a broker who is not saying yet."
              >
                <CompanySelect placeholder="Charterer" allowClear />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item name="brokerCompanyId" label="Broker">
                <CompanySelect placeholder="Broker" allowClear />
              </Form.Item>
            </Col>
          </Row>

          <Form.Item name="notes" label="Notes">
            <Input.TextArea rows={3} />
          </Form.Item>

          {record && (
            <RecordActions
              entity="cargo"
              name={record.commodity}
              deleteLoading={remove.isPending}
              deleteWarning="Any match decisions recorded against this cargo go with it."
              onDelete={() =>
                remove.mutate(record.id, {
                  onSuccess: () => {
                    onDeleted?.();
                    onClose();
                  },
                })
              }
            />
          )}
        </Form>
      </FormWithReference>
    </Modal>
  );
}
