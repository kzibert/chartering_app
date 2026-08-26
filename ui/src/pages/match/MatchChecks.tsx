import { Space, Tag, Tooltip, Typography } from 'antd';
import {
  CheckCircleFilled,
  CloseCircleFilled,
  QuestionCircleFilled,
} from '@ant-design/icons';
import type { MatchCheckResponse, MatchVerdict } from '../../api/types';

const VERDICT: Record<MatchVerdict, { color: string; icon: React.ReactNode; hint: string }> = {
  PASS: {
    color: '#389e0d',
    icon: <CheckCircleFilled />,
    hint: 'She meets what the cargo asked for',
  },
  FAIL: {
    color: '#cf1322',
    icon: <CloseCircleFilled />,
    hint: 'We hold data saying she does not fit — this is what rules the pairing out',
  },
  UNKNOWN: {
    color: '#8c8c8c',
    icon: <QuestionCircleFilled />,
    hint: 'Nothing on file to answer this. Not a no — it costs the pairing points and is worth going and finding out.',
  },
};

/**
 * The reasons behind a score.
 *
 * **This is the feature, not a debug panel.** A number saying 78 is worth nothing to a
 * broker; "draws 6.2m, berth takes 7.0m — arrives 4 Sep, laycan to 8 Sep" is something they
 * can act on or argue with. Every detail line names the actual figures for that reason.
 *
 * The three verdicts are visually distinct rather than a red/green pair, because the grey
 * one is the interesting one: it marks a gap in the data that is costing a real ship a real
 * place on a real list.
 */
export default function MatchChecks({ checks }: { checks: MatchCheckResponse[] }) {
  if (checks.length === 0) {
    return (
      <Typography.Text type="secondary">
        This cargo states nothing that can be tested against a vessel record — no quantity, no
        load point, no requirements. Add what the enquiry gave and it will start scoring.
      </Typography.Text>
    );
  }

  return (
    <Space direction="vertical" size={4} style={{ width: '100%' }}>
      {checks.map((c) => {
        const v = VERDICT[c.verdict];
        return (
          /* Wraps, and the reason is the phone: a detail line is "Draws 7.9m, berth takes
             7.0m" and at 360px it needs the width of the row rather than what is left of it
             beside the label. min-width:0 with it, or the flex item refuses to shrink below
             its longest word and takes the card out past the screen edge. */
          <div
            key={c.code}
            style={{ display: 'flex', gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}
          >
            <Tooltip title={v.hint}>
              <span style={{ color: v.color }}>{v.icon}</span>
            </Tooltip>
            <Typography.Text strong style={{ minWidth: 78 }}>
              {c.label}
            </Typography.Text>
            <Typography.Text
              type={c.verdict === 'UNKNOWN' ? 'secondary' : undefined}
              style={{ flex: '1 1 200px', minWidth: 0 }}
            >
              {c.detail}
            </Typography.Text>
          </div>
        );
      })}
    </Space>
  );
}

/** A compact count of what went wrong or unanswered, for a table row. */
export function CheckSummary({ checks }: { checks: MatchCheckResponse[] }) {
  const fails = checks.filter((c) => c.verdict === 'FAIL');
  const unknowns = checks.filter((c) => c.verdict === 'UNKNOWN');
  return (
    <Space size={4} wrap>
      {fails.map((c) => (
        <Tooltip key={c.code} title={c.detail}>
          <Tag color="red">{c.label.toLowerCase()}</Tag>
        </Tooltip>
      ))}
      {unknowns.map((c) => (
        <Tooltip key={c.code} title={c.detail}>
          <Tag>{c.label.toLowerCase()}?</Tag>
        </Tooltip>
      ))}
      {fails.length === 0 && unknowns.length === 0 && (
        <Typography.Text type="secondary">everything asked for checks out</Typography.Text>
      )}
    </Space>
  );
}
