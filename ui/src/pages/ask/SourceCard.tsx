import { useState } from 'react';
import { Button, Card, Space, Tag, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import dayjs from 'dayjs';
import OriginalEmail from '../../components/OriginalEmail';
import type { AskSource, AskSourceKind } from '../../api/ask';

const KIND: Record<AskSourceKind, { label: string; colour: string }> = {
  MAIL: { label: 'Mail', colour: 'blue' },
  POST: { label: 'Board', colour: 'purple' },
  CARGO: { label: 'Cargo', colour: 'green' },
  POSITION: { label: 'Position', colour: 'orange' },
};

/**
 * One passage the answer was written from.
 *
 * What it can open is decided by what the record is. A mail message and a board post are the
 * original text, so they open in the same viewer the Intake and Cargoes tabs use. A cargo or a
 * position is a row, and the tabs have no link to a single row yet, so the card names the record
 * and sends the reader to the tab it lives on rather than pretending it can open it.
 */
export default function SourceCard({
  source,
  highlighted,
  innerRef,
}: {
  source: AskSource;
  highlighted: boolean;
  innerRef: (el: HTMLDivElement | null) => void;
}) {
  const navigate = useNavigate();
  const [viewing, setViewing] = useState(false);
  const kind = KIND[source.kind];
  const canOpenOriginal = source.kind === 'MAIL' || source.kind === 'POST';

  return (
    <div ref={innerRef} id={`ask-source-${source.n}`}>
      <Card
        size="small"
        style={{
          // The highlight is what the citation tag promises: the source it names lights up where
          // the eye lands after the scroll. Border rather than background, so it reads on a card
          // that is already tinted by nothing.
          borderColor: highlighted ? '#1677ff' : undefined,
          boxShadow: highlighted ? '0 0 0 2px rgba(22,119,255,0.2)' : undefined,
          transition: 'border-color 0.3s, box-shadow 0.3s',
        }}
      >
        <Space direction="vertical" size={6} style={{ width: '100%' }}>
          <Space size={8} wrap style={{ width: '100%' }}>
            <Typography.Text strong>[{source.n}]</Typography.Text>
            <Tag color={kind.colour} style={{ marginInlineEnd: 0 }}>{kind.label}</Tag>
            {source.date && (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {dayjs(source.date).format('D MMM YYYY')}
              </Typography.Text>
            )}
          </Space>

          <Typography.Text strong style={{ wordBreak: 'break-word' }}>
            {source.title || 'Untitled'}
          </Typography.Text>

          {source.snippet && (
            // Three lines, then an expander: enough to recognise the passage, short enough that
            // six sources do not bury the answer above them.
            <Typography.Paragraph
              type="secondary"
              style={{ marginBottom: 0, fontSize: 13 }}
              ellipsis={{ rows: 3, expandable: true, symbol: 'more' }}
            >
              {source.snippet}
            </Typography.Paragraph>
          )}

          <Space size={8} wrap>
            {canOpenOriginal && (
              <Button size="small" onClick={() => setViewing(true)}>
                {source.kind === 'MAIL' ? 'Original email' : 'Original post'}
              </Button>
            )}
            {source.kind === 'CARGO' && (
              <>
                <Button size="small" onClick={() => navigate('/cargoes')}>Cargoes tab</Button>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Cargo #{source.id}. Find it there by its id.
                </Typography.Text>
              </>
            )}
            {source.kind === 'POSITION' && (
              <>
                <Button size="small" onClick={() => navigate('/open-fleet')}>Open fleet</Button>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Position #{source.id}. Find it there by the vessel.
                </Typography.Text>
              </>
            )}
          </Space>
        </Space>
      </Card>

      {source.kind === 'MAIL' && (
        <OriginalEmail open={viewing} onClose={() => setViewing(false)} mailMessageId={source.id} />
      )}
      {source.kind === 'POST' && (
        <OriginalEmail open={viewing} onClose={() => setViewing(false)} feedItemId={source.id} />
      )}
    </div>
  );
}
