import { useEffect } from 'react';
import {
  App,
  Button,
  Card,
  Col,
  Form,
  InputNumber,
  Popconfirm,
  Row,
  Space,
  Tag,
  Typography,
} from 'antd';
import { SaveOutlined, UndoOutlined } from '@ant-design/icons';
import { useMatchSettings, useMatchSettingsMutations } from '../../api/hooks';

interface FormValues {
  ballastSpeedKnots: number;
  portAllowanceHours: number;
  minUtilisationPercent: number;
  idealUtilisationPercent: number;
  idealBallastDays: number;
  maxBallastDays: number;
}

/**
 * What the Match tab assumes when it weighs a ship against a cargo.
 *
 * **Here rather than in the code**, because these are the numbers a broker argues with the
 * screen about. "That is not a handysize speed." "We do take part cargoes on the small
 * ships." Both are settled in the same sitting as the disagreement, and a constant would
 * make either one a redeploy.
 *
 * The floor is the one that changes what appears. Raise it and part cargoes stop being
 * suggested at all; lower it and they come back. It never hides a pairing outright — a ruled
 * out ship is still there behind "show ruled out", with the percentage that ruled her out
 * printed on the row.
 */
export default function MatchSettingsCard() {
  const { message: toast } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const query = useMatchSettings();
  const { update, reset } = useMatchSettingsMutations();

  const settings = query.data;

  // Reset from the server's answer so a save or a reset leaves the fields showing what is
  // actually stored, not what was typed.
  useEffect(() => {
    if (settings) {
      form.setFieldsValue({
        ballastSpeedKnots: settings.ballastSpeedKnots,
        portAllowanceHours: settings.portAllowanceHours,
        minUtilisationPercent: settings.minUtilisationPercent,
        idealUtilisationPercent: settings.idealUtilisationPercent,
        idealBallastDays: settings.idealBallastDays,
        maxBallastDays: settings.maxBallastDays,
      });
    }
  }, [settings, form]);

  const customised =
    settings != null &&
    (settings.ballastSpeedKnots !== settings.defaultBallastSpeedKnots ||
      settings.portAllowanceHours !== settings.defaultPortAllowanceHours ||
      settings.minUtilisationPercent !== settings.defaultMinUtilisationPercent ||
      settings.idealUtilisationPercent !== settings.defaultIdealUtilisationPercent ||
      settings.idealBallastDays !== settings.defaultIdealBallastDays ||
      settings.maxBallastDays !== settings.defaultMaxBallastDays);

  return (
    <Card
      title={
        <Space wrap>
          Matching ships to cargoes
          {customised ? <Tag color="blue">customised</Tag> : <Tag>using defaults</Tag>}
        </Space>
      }
      loading={query.isLoading}
      extra={
        <Space wrap>
          <Popconfirm
            title="Back to the defaults?"
            description={`${settings?.defaultBallastSpeedKnots ?? 11.5} knots, ${
              settings?.defaultPortAllowanceHours ?? 12
            }h at the ends, ruled out below ${
              settings?.defaultMinUtilisationPercent ?? 55
            }% full, full marks at ${
              settings?.defaultIdealUtilisationPercent ?? 85
            }%, ballast free to ${settings?.defaultIdealBallastDays ?? 3}d and ruled out past ${
              settings?.defaultMaxBallastDays ?? 15
            }d.`}
            onConfirm={() =>
              reset.mutate(undefined, { onSuccess: () => toast.success('Back to the defaults') })
            }
            disabled={!customised}
          >
            <Button icon={<UndoOutlined />} loading={reset.isPending} disabled={!customised}>
              Reset
            </Button>
          </Popconfirm>
          <Button
            type="primary"
            icon={<SaveOutlined />}
            loading={update.isPending}
            onClick={() => form.submit()}
          >
            Save
          </Button>
        </Space>
      }
    >
      <Form<FormValues>
        form={form}
        layout="vertical"
        onFinish={(v) => update.mutate(v, { onSuccess: () => toast.success('Saved') })}
      >
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="ballastSpeedKnots"
              label="Ballast speed"
              rules={[{ required: true, message: 'A speed is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  What a leg in miles becomes days at. Half a knot moves a five-day ballast by
                  four hours, which is inside the noise of a laycan quoted as a three-day
                  spread — so this is a working assumption, not a measurement.
                </Typography.Text>
              }
            >
              <InputNumber
                min={4}
                max={25}
                step={0.5}
                addonAfter="kn"
                style={{ width: '100%' }}
              />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="portAllowanceHours"
              label="Allowance at the two ends"
              rules={[{ required: true, message: 'A number of hours is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Off one berth, out through a pilot station, in through another and
                  alongside. Not the wait at a strait — a Bosphorus convoy is counted per
                  passage, so a Black Sea ship pays it and a Med one does not.
                </Typography.Text>
              }
            >
              <InputNumber min={0} max={168} addonAfter="h" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="minUtilisationPercent"
              label="Rule her out below"
              rules={[{ required: true, message: 'A percentage is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  <b>What stops a 14,000-tonner being offered for a 4,000-tonne parcel.</b>{' '}
                  She can lift it — that is the problem. Freight is earned by the tonne and
                  the ship is paid for whole, so at 29% full she earns 29% of what she costs
                  and no owner takes it. Measured against the most the cargo could load.
                </Typography.Text>
              }
            >
              <InputNumber min={0} max={100} addonAfter="% full" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="idealUtilisationPercent"
              label="Full marks at"
              rules={[{ required: true, message: 'A percentage is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Between the two figures a pairing passes and earns the share of the distance
                  it has come, which is what puts a 92%-full ship above an otherwise identical
                  60% one without ruling the second out. Part cargoes are real.
                </Typography.Text>
              }
            >
              <InputNumber min={0} max={100} addonAfter="% full" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
        <Row gutter={16}>
          <Col xs={24} md={12}>
            <Form.Item
              name="idealBallastDays"
              label="Near enough to cost nothing"
              rules={[{ required: true, message: 'A number of days is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Under this the leg is inside the noise of a laycan quoted as a spread, and
                  scoring her down for it would be splitting hairs with an estimate built on
                  an assumed speed. Above it the ballast costs the owner real money and the
                  score starts saying so.
                </Typography.Text>
              }
            >
              <InputNumber min={0} max={120} addonAfter="days" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
          <Col xs={24} md={12}>
            <Form.Item
              name="maxBallastDays"
              label="Rule her out past"
              rules={[{ required: true, message: 'A number of days is required' }]}
              extra={
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  <b>How far this desk will send a ship, and the default only.</b> A cargo can
                  carry its own figure on its record, which wins for that enquiry — the parcel
                  worth crossing an ocean for and the one nobody would cross the Med for are
                  both an ordinary week. Between the two figures a pairing passes and earns the
                  share of the distance it has come, which is what puts a ship two days off the
                  berth above one nine days off that also makes the laycan.
                </Typography.Text>
              }
            >
              <InputNumber min={0} max={120} addonAfter="days" style={{ width: '100%' }} />
            </Form.Item>
          </Col>
        </Row>
      </Form>

      <Typography.Paragraph type="secondary" style={{ marginBottom: 0 }}>
        Where both ends of a pairing name a berth this database has placed, the ballast leg is
        measured across the sea network — miles, and the straits on the way, with the wait for
        a Bosphorus convoy in the total. Where either end is only a water, which is how most
        circulars write a position, it falls back to the round number of days between two
        trade areas. Either way the leg is counted from today where her open dates have
        already passed — a position stays LIVE after its dates run out, and a ballast counted
        from a day in August would present her on one too. Nothing here is stored against a
        pairing: every score is computed on the request, because one goes stale the moment a
        position or a cargo moves.
      </Typography.Paragraph>
    </Card>
  );
}
