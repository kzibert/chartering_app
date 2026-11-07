import { Tag, Typography } from 'antd';

/**
 * The answer as written, with each [n] made a tag that points at source n below it.
 *
 * Plain text on purpose: the answer is the model's prose and nothing else. A markdown renderer
 * would turn a stray asterisk or a pipe in a figure into formatting nobody asked for, and the
 * only thing the reader needs beyond the words is where each claim came from, which the tags carry.
 *
 * A bracketed number that names no source on this answer stays as typed. The model can write [7]
 * when there were five passages; that is a fact about the answer worth showing as it is, not a tag
 * that goes nowhere.
 */
export default function AskAnswer({
  text,
  sourceNumbers,
  onCite,
}: {
  text: string;
  /** The n of every source on this answer. Tags are made only for these. */
  sourceNumbers: number[];
  onCite: (n: number) => void;
}) {
  const parts = text.split(/(\[\d+\])/g);

  return (
    <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', fontSize: 15, marginBottom: 0 }}>
      {parts.map((part, i) => {
        const m = /^\[(\d+)\]$/.exec(part);
        const n = m ? Number(m[1]) : NaN;
        if (!m || !sourceNumbers.includes(n)) return <span key={i}>{part}</span>;
        return (
          // A Tag rather than a link: it is a pointer to the list on this page, and a link would
          // read as leaving the page. Keyboard reachable, so the citations work without a mouse.
          <Tag
            key={i}
            color="blue"
            role="button"
            tabIndex={0}
            onClick={() => onCite(n)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault();
                onCite(n);
              }
            }}
            style={{ cursor: 'pointer', marginInlineEnd: 2 }}
          >
            [{n}]
          </Tag>
        );
      })}
    </Typography.Paragraph>
  );
}
