import { useEffect, useState } from 'react';
import { App, Alert, Button, Card, Input, Popconfirm, Radio, Space, Tag, Typography } from 'antd';
import { DeleteOutlined, SaveOutlined } from '@ant-design/icons';
import { useQueryClient } from '@tanstack/react-query';
import { useCapabilities } from '../../capabilities/store';
import {
  forgetParserKey,
  keyHint,
  readsWithCloud,
  setParserRoute,
  useParserRoute,
  type ParserProvider,
} from '../../parser/route';

/**
 * Which model reads mail for <em>you</em>, and your own key for the cloud one.
 *
 * A card of its own, apart from "Reading the mail", because it is about the person rather than
 * the installation: the key is yours, it stays in this browser (parser/route.ts) and is sent
 * only with your Intake requests, and the server never stores it. That is also why this card
 * shows when Intake does not exist for you yet — on the hosted instance the cloud model is the
 * only one there is, and saving a key here is what brings the Intake tab in.
 *
 * Where the deployment offers both (the office), it is a choice; where it offers only the cloud
 * one (Render), it is just the key.
 */
export default function ParserRouteCard() {
  const { message: toast } = App.useApp();
  const queryClient = useQueryClient();
  const caps = useCapabilities().data;
  const route = useParserRoute();
  const [provider, setProvider] = useState<ParserProvider>(route.provider);
  const [key, setKey] = useState('');

  useEffect(() => setProvider(route.provider), [route.provider]);

  if (!caps || (!caps.parserLocal && !caps.parserCloud)) return null;

  const both = caps.parserLocal && caps.parserCloud;
  // Only the cloud offered: the choice is made for the person, and the key is the whole card.
  const chosen: ParserProvider = !caps.parserLocal ? 'CLOUD' : !caps.parserCloud ? 'LOCAL' : provider;
  const typed = key.trim();

  // Every Intake query was answered for the old route - its status, its counts, whether the tab
  // exists at all - so all of it is asked again.
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['intake'] });

  const save = () => {
    if (chosen === 'CLOUD' && !typed && !route.apiKey) {
      toast.warning('Paste your API key first');
      return;
    }
    setParserRoute({ provider: chosen, apiKey: typed || route.apiKey });
    setKey('');
    refresh();
    toast.success(chosen === 'CLOUD' ? 'Reading with the cloud model and your key' : 'Reading with the local model');
  };

  const forget = () => {
    forgetParserKey();
    setKey('');
    refresh();
    toast.success('Key removed from this browser');
  };

  return (
    <Card
      title={
        <Space wrap>
          Your parser
          {readsWithCloud(route) ? (
            <Tag color="blue">cloud, your key {keyHint(route.apiKey)}</Tag>
          ) : caps.parserLocal ? (
            <Tag>local model</Tag>
          ) : (
            <Tag color="orange">no key yet</Tag>
          )}
        </Space>
      }
      extra={
        <Button type="primary" icon={<SaveOutlined />} onClick={save}>
          Save
        </Button>
      }
    >
      {both && (
        <Radio.Group
          value={provider}
          onChange={(e) => setProvider(e.target.value)}
          style={{ marginBottom: 16 }}
        >
          <Radio.Button value="LOCAL">The office model</Radio.Button>
          <Radio.Button value="CLOUD">A cloud model, with my key</Radio.Button>
        </Radio.Group>
      )}

      {!caps.parserLocal && !route.apiKey && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="This deployment reads mail with a cloud model under your own key"
          description="There is no office model here. Paste a Groq API key below and the Intake tab appears; every email it reads is billed to that key."
        />
      )}

      {chosen === 'CLOUD' && (
        <Space direction="vertical" style={{ width: '100%' }}>
          <Input.Password
            value={key}
            onChange={(e) => setKey(e.target.value)}
            placeholder={route.apiKey ? `Saved key ${keyHint(route.apiKey)} — paste a new one to replace it` : 'Paste your API key (gsk_…)'}
            autoComplete="new-password"
            spellCheck={false}
            onPressEnter={save}
          />
          <Space size={8} wrap>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Kept in this browser for your login only, and sent with your Intake requests — never
              stored on the server. Nothing reads with it on a timer: mail is read when you press
              Parse now, Parse everything, or pick messages on the Mailbox tab.
            </Typography.Text>
            {route.apiKey && (
              <Popconfirm
                title="Remove your key from this browser?"
                description="Intake reads with the office model again where there is one; otherwise the tab goes until a key is saved."
                okText="Remove"
                okButtonProps={{ danger: true }}
                onConfirm={forget}
              >
                <Button size="small" danger icon={<DeleteOutlined />}>
                  Remove key
                </Button>
              </Popconfirm>
            )}
          </Space>
        </Space>
      )}

      {chosen === 'LOCAL' && (
        <Typography.Text type="secondary">
          The office model reads your mail, the same one the timer reads with. Nothing is billed to
          anyone.
        </Typography.Text>
      )}
    </Card>
  );
}
