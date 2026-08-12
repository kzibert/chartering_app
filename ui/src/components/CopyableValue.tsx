import { Typography } from 'antd';

/** Renders a contact value (email/phone) with an inline copy button. */
export default function CopyableValue({
  value,
  highlight,
}: {
  value?: string;
  /** Case-insensitive substring to mark — the term the row was found by. */
  highlight?: string;
}) {
  if (!value) return <>—</>;
  return (
    <Typography.Text copyable={{ text: value, tooltips: ['Copy', 'Copied'] }}>
      {highlight ? mark(value, highlight) : value}
    </Typography.Text>
  );
}

function mark(value: string, term: string) {
  const at = value.toLowerCase().indexOf(term.toLowerCase());
  if (at < 0) return value;
  return (
    <>
      {value.slice(0, at)}
      <mark>{value.slice(at, at + term.length)}</mark>
      {value.slice(at + term.length)}
    </>
  );
}
