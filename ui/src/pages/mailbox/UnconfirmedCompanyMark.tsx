import { Tooltip } from 'antd';
import { ClockCircleOutlined } from '@ant-design/icons';

/**
 * The mark beside a message's company when that company's record is not confirmed yet.
 *
 * The sender was matched to the firm through its contacts, and most of those rows arrived
 * by import or intake rather than by somebody checking them — so a message filed under an
 * unchecked firm is worth a second look before it is answered as that firm. Same clock and
 * colour as ConfirmTag's "Needs confirm", so the two read as one signal; an icon rather than
 * the tag because it sits inside a 200px column beside a sixty-character shipowner's name.
 */
export default function UnconfirmedCompanyMark() {
  return (
    <Tooltip title="Company not confirmed yet">
      <ClockCircleOutlined style={{ color: '#faad14', flex: 'none' }} aria-label="Company not confirmed yet" />
    </Tooltip>
  );
}
