import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Empty,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  message,
} from 'antd';
import { GlobalOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useIntakeMutations } from '../../intake/store';
import type { IntakeItemResponse, LookupProposal } from '../../api/intake';

/**
 * What an outside source found about this hull, and what to believe of it.
 *
 * <b>Everything here is marked as coming from off the premises, deliberately and
 * repeatedly.</b> A deadweight accepted from a public ship database is indistinguishable
 * from one a broker checked the moment it lands in the column — so the origin is on the
 * card, on every value, on the button that writes it, and in the change set the write
 * creates. The vessel's History tab will say "Web lookup (vesselfinder) IMO 9014561" beside
 * the field months later, which is the only way the question "where did this figure come
 * from" has an answer at all.
 *
 * <b>Applying is its own button, not part of accepting the email.</b> Two origins, two
 * writes, two change sets. Folding them together would save a click and lose the whole point.
 *
 * The confidence figure is shown with what it rests on rather than alone: a hull matched on
 * the name and nothing else scores 100%, because the one thing that could be checked agreed
 * — and the name is what was searched for, so that is the query coming back rather than
 * evidence. `corroborated` is what separates the two, and it is said in words.
 */
export default function FromTheWeb({
  item,
  vesselId,
  chosen: chosenProp,
  onChange,
}: {
  item: IntakeItemResponse;
  /** The hull to write to. Absent on a new vessel until she has been created. */
  vesselId?: number;
  /**
   * The ticked fields, when the drawer around this card is holding them.
   *
   * <b>Lifted so one button can answer the whole screen.</b> The email's figures and the
   * web's are two writes with two change sets and that separation is deliberate — it is the
   * only thing that later says which column came from where. What was not deliberate was
   * making a person click twice to say one thing. The footer fires both, in order, each
   * keeping its own change set; this card keeps its own button for taking the web's figures
   * without answering the item at all.
   */
  chosen?: string[];
  onChange?: (fields: string[]) => void;
}) {
  const lookup = item.lookup;
  const { lookup: runLookup, applyLookup } = useIntakeMutations();
  const proposals = lookup?.proposals ?? [];
  const [own, setOwn] = useState<string[]>([]);
  const chosen = chosenProp ?? own;
  const setChosen = onChange ?? setOwn;

  // Empty columns ticked, disagreements not. Filling a blank from a public database is
  // ordinary; overwriting a figure somebody here checked is a decision, and it should be
  // made rather than inherited from a default.
  useEffect(() => {
    setChosen(proposals.filter((p) => !p.differs).map((p) => p.field));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lookup?.id, proposals.length]);

  const search = (
    <Button
      size="small"
      icon={<SearchOutlined />}
      loading={runLookup.isPending}
      onClick={() =>
        runLookup.mutate(item.id, {
          onSuccess: (updated) =>
            message.info(
              updated.lookup?.status === 'OK'
                ? `Found ${updated.lookup.matched?.name ?? 'a match'}.`
                : updated.lookup?.status === 'SKIPPED'
                  ? 'She is already identified here and in the email — a search could only agree.'
                  : 'Nothing matched confidently enough to offer.',
            ),
        })
      }
    >
      {lookup ? 'Search again' : 'Look her up'}
    </Button>
  );

  if (!lookup) {
    return (
      <Card
        size="small"
        style={{ marginBottom: 16 }}
        title={
          <Space>
            <GlobalOutlined />
            From the web
          </Space>
        }
        extra={search}
      >
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          Not looked up yet. Hulls waiting in this queue are searched for automatically; this
          runs one now.
        </Typography.Text>
      </Card>
    );
  }

  const header = (
    <Space wrap size={6}>
      <GlobalOutlined />
      From the web
      <Tag color="purple">{lookup.provider}</Tag>
      {lookup.status === 'OK' && lookup.confidence != null && (
        <Tooltip
          title={
            lookup.corroborated
              ? 'The share of the checkable facts that agreed — name, build year, deadweight and flag.'
              : 'Only the name could be checked, and the name is what was searched for. Treat this as unverified however high the figure looks.'
          }
        >
          <Tag color={lookup.corroborated ? 'green' : 'orange'}>
            {lookup.confidence}% {lookup.corroborated ? 'match' : 'on the name alone'}
          </Tag>
        </Tooltip>
      )}
      {lookup.fetchedAt && (
        <Typography.Text type="secondary" style={{ fontSize: 11 }}>
          {dayjs(lookup.fetchedAt).format('D MMM HH:mm')}
        </Typography.Text>
      )}
    </Space>
  );

  return (
    <Card size="small" style={{ marginBottom: 16 }} title={header} extra={search}>
      {lookup.status === 'FAILED' && (
        <Alert
          type="warning"
          showIcon
          message="The source could not be read"
          description={
            <>
              {lookup.error}
              <br />
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                This reads a public page that was never offered as an interface, so it breaks
                without notice. Nothing else on this screen is affected.
              </Typography.Text>
            </>
          }
        />
      )}

      {lookup.status === 'SKIPPED' && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          Not searched for. The email and the record both name her by IMO, so a search could
          only have agreed with them — and this reads a public page at somebody else&rsquo;s
          expense. Search anyway if you want a second opinion on her particulars.
        </Typography.Text>
      )}

      {lookup.status === 'NO_MATCH' && (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description={
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Searched for &ldquo;{lookup.query}&rdquo; and nothing came back confident enough
              to offer. Much of this fleet is small tonnage a public database does not carry —
              and a plausible wrong ship would be worse than none.
            </Typography.Text>
          }
        />
      )}

      {lookup.status === 'OK' && lookup.matched && (
        <>
          {lookup.onFileVesselId && (
            <Alert
              type="success"
              showIcon
              style={{ marginBottom: 12 }}
              message={`She may already be on file as ${lookup.onFileVesselName}`}
              description="A vessel here already carries this IMO. She is not a new hull — she has been renamed. Close this and link the position to that ship instead of creating a second one; the name from the email is kept as a former name."
            />
          )}

          <Space direction="vertical" size={4} style={{ width: '100%', marginBottom: 12 }}>
            <Space wrap size={8}>
              <Typography.Text strong>{lookup.matched.name}</Typography.Text>
              {lookup.matched.imo && <Tag>IMO {lookup.matched.imo}</Tag>}
              {lookup.matched.sourceUrl && (
                <Typography.Link
                  href={lookup.matched.sourceUrl}
                  target="_blank"
                  rel="noreferrer noopener"
                >
                  open the source
                </Typography.Link>
              )}
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {[
                lookup.matched.vesselType,
                lookup.matched.yearBuilt ? `built ${lookup.matched.yearBuilt}` : null,
                lookup.matched.deadweightTonnage
                  ? `${lookup.matched.deadweightTonnage} t dwt`
                  : null,
                lookup.matched.grossTonnage ? `${lookup.matched.grossTonnage} gt` : null,
                lookup.matched.flag,
              ]
                .filter(Boolean)
                .join(' · ')}
            </Typography.Text>
          </Space>

          {(lookup.reasons?.length ?? 0) > 0 && (
            <Typography.Paragraph style={{ marginBottom: 4, fontSize: 12 }}>
              Agrees on: {lookup.reasons!.join('; ')}
            </Typography.Paragraph>
          )}
          {(lookup.disagreements?.length ?? 0) > 0 && (
            <Typography.Paragraph type="warning" style={{ marginBottom: 8, fontSize: 12 }}>
              Disagrees on: {lookup.disagreements!.join('; ')}
            </Typography.Paragraph>
          )}

          {proposals.length === 0 ? (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Nothing it holds is missing from the record.
            </Typography.Text>
          ) : (
            <>
              <Table<LookupProposal>
                rowKey={(p) => p.field}
                size="small"
                pagination={false}
                dataSource={proposals}
                rowSelection={{
                  selectedRowKeys: chosen,
                  onChange: (keys) => setChosen(keys as string[]),
                  columnTitle: (
                    <Tooltip title="Tick what to take from the web">
                      <span>Take</span>
                    </Tooltip>
                  ),
                }}
                columns={[
                  { title: 'Field', dataIndex: 'label', key: 'label', width: 130 },
                  {
                    title: 'On file',
                    dataIndex: 'current',
                    key: 'current',
                    render: (v: string) => v ?? <Typography.Text type="secondary">—</Typography.Text>,
                  },
                  {
                    title: 'The web says',
                    key: 'incoming',
                    render: (_, p) => (
                      <Space size={4}>
                        <Typography.Text strong>{p.incoming}</Typography.Text>
                        {p.differs && (
                          <Tooltip title="The record already holds something else. Overwriting a figure somebody here checked with one off a public page is a decision worth making deliberately.">
                            <Tag color="orange" style={{ marginInlineEnd: 0 }}>
                              differs
                            </Tag>
                          </Tooltip>
                        )}
                      </Space>
                    ),
                  },
                ]}
              />

              <Space style={{ marginTop: 12 }} wrap>
                <Button
                  icon={<GlobalOutlined />}
                  loading={applyLookup.isPending}
                  disabled={chosen.length === 0}
                  onClick={() =>
                    applyLookup.mutate(
                      { id: item.id, body: { fields: chosen, vesselId } },
                      {
                        onSuccess: () =>
                          message.success('Written, and recorded as coming from the web.'),
                      },
                    )
                  }
                >
                  Take {chosen.length} field{chosen.length === 1 ? '' : 's'} from the web
                </Button>
                {!vesselId && !item.vesselId && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    Create her or link her to a ship on file first — there is no record to
                    write to yet.
                  </Typography.Text>
                )}
              </Space>

              <div style={{ marginTop: 8 }}>
                <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                  Written as its own change, named for its source, so the vessel&rsquo;s
                  History tab shows which figures came off the web and from where.
                </Typography.Text>
              </div>
            </>
          )}
        </>
      )}
    </Card>
  );
}
