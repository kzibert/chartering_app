import { Typography } from 'antd';

/** Renders a contact value (email/phone) with an inline copy button. */
export default function CopyableValue({ value }: { value?: string }) {
  if (!value) return <>—</>;
  return (
    <Typography.Text copyable={{ text: value, tooltips: ['Copy', 'Copied'] }}>
      {value}
    </Typography.Text>
  );
}
