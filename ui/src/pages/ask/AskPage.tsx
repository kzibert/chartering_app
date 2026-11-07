import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Alert, Button, Card, Input, Space, Spin, Typography } from 'antd';
import { MessageOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { askApi, type AskResponse } from '../../api/ask';
import { useAskStatus } from '../../ask/store';
import { useIsMobile } from '../../responsive/useIsMobile';
import AskAnswer from './AskAnswer';
import AskIndexCard from './AskIndexCard';
import SourceCard from './SourceCard';

/**
 * Example questions. The broker name is a placeholder: a real firm's name in a sample would be
 * a real firm's business in a screenshot. Clicking one fills the box rather than asking it, so the
 * placeholder is never sent as though it were the question.
 */
const EXAMPLES = [
  'Which Supramax are open in the East Med in the next 10 days?',
  'What did ACME Chartering offer last week?',
  'Which cargoes of wheat from the Black Sea are open?',
];

/** How many earlier questions the session keeps. In memory only: nothing about a question is stored. */
const HISTORY_LIMIT = 20;

/** One question and the answer it got, as the history holds it. */
interface Turn {
  key: number;
  question: string;
  response: AskResponse;
}

function errorText(err: unknown): string {
  if (isAxiosError(err)) return err.response?.data?.message ?? err.message;
  return 'The question could not be answered.';
}

/**
 * Ask the desk: one question at a time, answered from the desk's own text with the passages it
 * was written from listed beneath.
 *
 * Only offered where the deployment has the feature (the parser and the embedding server are both
 * configured, which the status endpoint reports). The page is registered regardless, so a bookmarked URL explains itself rather
 * than bouncing to the dashboard, the same as Analysis and Intake.
 *
 * Nothing about a question leaves this component except the request itself. It is not in the query
 * cache (a mutation, not a query), not in browser storage, and not on the console.
 */
export default function AskPage() {
  const status = useAskStatus();
  const isMobile = useIsMobile();
  const [question, setQuestion] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [shownKey, setShownKey] = useState<number>();
  const [highlight, setHighlight] = useState<number>();
  // Each source's card, by its n, so a citation can scroll to it.
  const cards = useRef(new Map<number, HTMLDivElement>());
  const seq = useRef(0);

  const ask = useMutation({
    mutationFn: (q: string) => askApi.ask(q),
    onSuccess: (response, q) => {
      const key = ++seq.current;
      setTurns((t) => [{ key, question: q, response }, ...t].slice(0, HISTORY_LIMIT));
      setShownKey(key);
      setHighlight(undefined);
      setQuestion('');
    },
  });

  // The highlight is a pointer, not a state the reader must clear: it fades on its own.
  useEffect(() => {
    if (highlight == null) return;
    const t = setTimeout(() => setHighlight(undefined), 2500);
    return () => clearTimeout(t);
  }, [highlight]);

  if (status.isLoading) return null;

  if (status.data?.enabled !== true) {
    return (
      <Alert
        type="info"
        showIcon
        message="Ask the desk is not part of this deployment"
        description="It answers from the desk's own text using the local model and the embedding server, neither of which runs here."
      />
    );
  }

  const shown = turns.find((t) => t.key === shownKey) ?? turns[0];
  const sources = shown?.response.sources ?? [];
  const busy = ask.isPending;

  const submit = (text = question) => {
    const q = text.trim();
    if (!q || busy) return;
    ask.mutate(q);
  };

  // Enter asks, Shift+Enter is a new line. The composition check is for IME input, where Enter
  // confirms a word and must not send the question half written.
  const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      submit();
    }
  };

  const cite = (n: number) => {
    setHighlight(n);
    cards.current.get(n)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
  };

  // A 409 means the parser sweep holds the model; the server's own message says so, and the
  // advice is to wait for it rather than to retry at once into the same conflict.
  const busyWithSweep = isAxiosError(ask.error) && ask.error.response?.status === 409;

  return (
    <Space direction="vertical" size={16} style={{ width: '100%', maxWidth: 880 }}>
      <div>
        <Typography.Title level={4} style={{ margin: 0 }}>Ask the desk</Typography.Title>
        <Typography.Text type="secondary">
          Answers come from the desk's own mail, board posts, cargoes and positions. Each claim is
          tagged with the passage it came from.
        </Typography.Text>
      </div>

      <AskIndexCard />

      <Card size="small">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <div style={{ display: 'flex', flexDirection: isMobile ? 'column' : 'row', gap: 8, alignItems: 'stretch' }}>
            <Input.TextArea
              value={question}
              onChange={(e) => setQuestion(e.target.value)}
              onKeyDown={onKeyDown}
              placeholder="Ask a question about the desk's text"
              maxLength={1000}
              autoSize={{ minRows: 2, maxRows: 6 }}
              aria-label="Question"
              style={{ flex: 1, minWidth: 0, width: isMobile ? '100%' : undefined }}
            />
            <Button
              type="primary"
              icon={<MessageOutlined />}
              disabled={!question.trim() || busy}
              onClick={() => submit()}
              block={isMobile}
              style={{ height: 'auto', minHeight: 32 }}
            >
              Ask
            </Button>
          </div>

          {!shown && !busy && (
            <Space size={8} wrap>
              {EXAMPLES.map((ex) => (
                <Button
                  key={ex}
                  shape="round"
                  size="small"
                  onClick={() => setQuestion(ex)}
                  style={{ whiteSpace: 'normal', height: 'auto', textAlign: 'left', padding: '4px 12px' }}
                >
                  {ex}
                </Button>
              ))}
            </Space>
          )}
        </Space>
      </Card>

      {ask.isError && (
        <Alert
          type={busyWithSweep ? 'warning' : 'error'}
          showIcon
          message={errorText(ask.error)}
          description={busyWithSweep ? 'The parser is reading new mail. Try again in a minute.' : undefined}
        />
      )}

      {busy && (
        <Card size="small">
          <Space size={12}>
            <Spin />
            <Typography.Text>Reading the desk's mail…</Typography.Text>
          </Space>
        </Card>
      )}

      {shown && !busy && (
        <>
          <Typography.Text strong style={{ wordBreak: 'break-word' }}>{shown.question}</Typography.Text>

          <Card size="small">
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <AskAnswer
                text={shown.response.answer}
                sourceNumbers={sources.map((s) => s.n)}
                onCite={cite}
              />
              {shown.response.model && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  Written by {shown.response.model}
                </Typography.Text>
              )}
            </Space>
          </Card>

          {sources.length === 0 ? (
            <Alert type="info" showIcon message="Nothing on the desk was cited for this answer." />
          ) : (
            <Space direction="vertical" size={8} style={{ width: '100%' }}>
              <Typography.Text type="secondary">Sources ({sources.length})</Typography.Text>
              {sources.map((s) => (
                <SourceCard
                  key={s.n}
                  source={s}
                  highlighted={highlight === s.n}
                  innerRef={(el) => {
                    if (el) cards.current.set(s.n, el);
                    else cards.current.delete(s.n);
                  }}
                />
              ))}
            </Space>
          )}

          {turns.length > 1 && (
            <Card size="small" title="Earlier questions">
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                {turns.map((t) => (
                  <Button
                    key={t.key}
                    type="link"
                    onClick={() => {
                      setShownKey(t.key);
                      setHighlight(undefined);
                    }}
                    style={{
                      height: 'auto',
                      padding: 0,
                      textAlign: 'left',
                      whiteSpace: 'normal',
                      fontWeight: t.key === shown.key ? 600 : 400,
                    }}
                  >
                    {t.question}
                  </Button>
                ))}
              </Space>
            </Card>
          )}
        </>
      )}
    </Space>
  );
}
