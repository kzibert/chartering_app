import { useEffect } from 'react';
import type { ReactNode } from 'react';
import { Alert, Col, DatePicker, Form, Input, Modal, Row, Select, Typography } from 'antd';
import dayjs from 'dayjs';
import { usePositionMutations } from '../../api/hooks';
import FormWithReference from '../../components/FormWithReference';
import CompanySelect from '../../components/CompanySelect';
import PortSelect from '../../components/PortSelect';
import TradeAreaSelect from '../../components/TradeAreaSelect';
import VesselSelect from '../../components/VesselSelect';
import { toPositionRequest } from '../../api/positions';
import { POSITION_STATUS_OPTIONS } from './status';
import type { VesselPositionRequest, VesselPositionResponse } from '../../api/types';

interface Props {
  open: boolean;
  editing?: VesselPositionResponse | null;
  /** Prefilled fields — the vessel, when recording a position from her own drawer. */
  defaults?: Partial<VesselPositionRequest>;
  /**
   * Pin the vessel picker. Set when the form is opened from one ship's own record: the
   * vessel is not a choice there, and leaving it editable means a stray click files this
   * reading against a different hull with nothing on screen saying so.
   */
  lockVessel?: boolean;
  /**
   * Source text to keep on screen while the form is filled in — the email this reading is
   * being typed out of. See {@link FormWithReference}; absent, the dialog is unchanged.
   */
  reference?: ReactNode;
  onClose: () => void;
}

/**
 * Recording an opening position.
 *
 * **Only the vessel is required.** "MV LADY LEYLA SPOT AT MARMARA" is a complete position as
 * far as the market is concerned: no port, no dates, and nothing about it is incomplete.
 * Every other field is what this particular list happened to say.
 *
 * The banner about superseding is on screen rather than buried in an API doc because it is
 * the one non-obvious consequence of saving: a second reading from the same broker replaces
 * their first, and a reading from a different broker does not. Somebody typing GN's Monday
 * list and Interscan's Tuesday list needs to know that before they wonder where a row went.
 */
export default function PositionForm({
  open,
  editing,
  defaults,
  lockVessel,
  reference,
  onClose,
}: Props) {
  const [form] = Form.useForm();
  const { create, update } = usePositionMutations();

  useEffect(() => {
    if (!open) return;
    form.resetFields();
    if (editing) {
      const body = toPositionRequest(editing);
      form.setFieldsValue({
        ...body,
        openFrom: editing.openFrom ? dayjs(editing.openFrom) : undefined,
        openTo: editing.openTo ? dayjs(editing.openTo) : undefined,
        reportedAt: editing.reportedAt ? dayjs(editing.reportedAt) : undefined,
      });
    } else {
      // A caller's defaults are a request body, so its dates are the ISO strings the API
      // takes; the pickers want dayjs. Recording from an email leans on this — the reading
      // is as old as the mail it came out of, not as old as the moment it was typed.
      form.setFieldsValue({
        status: 'LIVE',
        ...defaults,
        openFrom: defaults?.openFrom ? dayjs(defaults.openFrom) : undefined,
        openTo: defaults?.openTo ? dayjs(defaults.openTo) : undefined,
        reportedAt: defaults?.reportedAt ? dayjs(defaults.reportedAt) : undefined,
      });
    }
  }, [open, editing, defaults, form]);

  const submit = (values: Record<string, unknown>) => {
    const day = (v: unknown) => (v ? (v as dayjs.Dayjs).format('YYYY-MM-DD') : undefined);
    const body: VesselPositionRequest = {
      ...(values as unknown as VesselPositionRequest),
      openFrom: day(values.openFrom),
      openTo: day(values.openTo),
      // Kept as a full instant rather than a day: two lists read on the same afternoon have
      // to order against each other, and that is what decides which one is current.
      reportedAt: values.reportedAt ? (values.reportedAt as dayjs.Dayjs).toISOString() : undefined,
      // Provenance is not a field anybody types, so it never reaches `values` — antd
      // returns the registered fields and nothing else. It is carried straight from the
      // defaults the caller opened the form with, and only on a create: the API keeps an
      // existing link when this is null, which is what makes "how it reached the desk" a
      // fact that survives editing the reading afterwards.
      sourceMailMessageId: editing ? undefined : defaults?.sourceMailMessageId,
    };
    const done = { onSuccess: onClose };
    if (editing) update.mutate({ id: editing.id, body }, done);
    else create.mutate(body, done);
  };

  return (
    <Modal
      open={open}
      title={editing ? `Edit position — ${editing.vessel.name}` : 'New position'}
      okText="Save"
      width={reference ? 1180 : 780}
      confirmLoading={create.isPending || update.isPending}
      onCancel={onClose}
      onOk={() => form.submit()}
      destroyOnClose
    >
      <FormWithReference reference={reference}>
        <Form form={form} layout="vertical" onFinish={submit}>
          <Row gutter={12}>
            <Col xs={24} md={16}>
              <Form.Item
                name="vesselId"
                label="Vessel"
                rules={[{ required: true, message: 'vessel is required' }]}
                tooltip="Searches former names too, so a list naming a ship we hold under a name she was renamed out of still finds her rather than creating a second one."
              >
                {/* Locked while editing: moving a position to a different hull is not an edit,
                    it is two separate facts, and doing it silently would rewrite one ship's
                    history into another's. Locked too when the form was opened from a ship's
                    own record, where the vessel was never a choice. */}
                <VesselSelect disabled={!!editing || !!lockVessel} />
              </Form.Item>
            </Col>
            <Col xs={12} md={8}>
              <Form.Item name="status" label="Status">
                <Select options={POSITION_STATUS_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Text type="secondary">Where she opens</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={24} md={8}>
              <Form.Item name="openPortId" label="Open port">
                <PortSelect />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="openPortText"
                label="Open, as written"
                tooltip="What the list said. Kept beside the port so a place the ports table has no row for is not lost — several lists name a country rather than a berth."
              >
                <Input placeholder="SALERNO, MOROCCO, ECUK…" />
              </Form.Item>
            </Col>
            <Col xs={24} md={8}>
              <Form.Item
                name="openAreaId"
                label="Open area"
                tooltip="Only needed when no port is named. With a port chosen, the port's own area is what matching uses."
              >
                <TradeAreaSelect />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Text type="secondary">When</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={12} md={6}>
              <Form.Item name="openFrom" label="From">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={12} md={6}>
              <Form.Item name="openTo" label="To">
                <DatePicker style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="openText"
                label="Dates, as written"
                tooltip='"SPOT" and "PPT" are real answers that name no day, and they describe the promptest tonnage on any list. Worth keeping whether or not dates were also given.'
              >
                <Input placeholder="01 / 02 SEPT, SPOT, PPT…" />
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={12}>
            <Col xs={24} md={8}>
              <Form.Item
                name="lastCargo"
                label="Last cargo"
                tooltip="Asked about constantly and recorded almost never: a hold that last had cement in it is not offered for grain without a cleaning conversation."
              >
                <Input placeholder="grain, cement, scrap…" />
              </Form.Item>
            </Col>
            <Col xs={24} md={16}>
              <Form.Item
                name="cargoPreferences"
                label="Cargo preferences for the next voyage"
                tooltip="Free text on purpose: 'prefers grain, no scrap, no Israel, min 20 days duration' is one sentence in an email and would be five badly fitting boxes here. The hard constraints Match can test live on the vessel record as fitted flags."
              >
                <Input placeholder="prefers grain, no scrap, no Israel…" />
              </Form.Item>
            </Col>
          </Row>

          <Typography.Text type="secondary">Who told us, and when</Typography.Text>
          <Row gutter={12} style={{ marginTop: 8 }}>
            <Col xs={24} md={12}>
              <Form.Item
                name="reportedByCompanyId"
                label="Reported by"
                tooltip="A position is only as good as its source — a list from the owner is worth more than the same line forwarded by a third broker."
              >
                <CompanySelect allowClear placeholder="Broker or owner" />
              </Form.Item>
            </Col>
            <Col xs={24} md={12}>
              <Form.Item
                name="reportedAt"
                label="Reported at"
                tooltip="When we were told, which is not when this row was written. A list read out of a three-day-old email is three days old, and the fleet list shows that."
              >
                <DatePicker showTime style={{ width: '100%' }} placeholder="Now" />
              </Form.Item>
            </Col>
          </Row>

          {!editing && (
            <Alert
              type="info"
              showIcon
              style={{ marginBottom: 16 }}
              message="Saving this replaces the same reporter's previous live position for this vessel, and nobody else's. Two brokers who disagree stay on file as two readings; one broker repeating themselves is one."
            />
          )}

          <Form.Item name="notes" label="Notes">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </FormWithReference>
    </Modal>
  );
}
