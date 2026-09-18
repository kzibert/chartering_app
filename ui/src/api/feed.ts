import { client, cleanParams } from './client';
import type { PageResponse } from './types';

/**
 * The Feed: outside sources, topics, and the local model's summaries of them.
 *
 * Everything here reads and writes on every deployment. Fetching, summarising and asking the
 * model its window answer 404 where `analysisEnabled` is false — the page reads the status and
 * leaves those buttons out rather than offering ones that fail.
 */

export type FeedSourceKind = 'TELEGRAM' | 'RSS' | 'WEBSITE';

export interface FeedSource {
  id: number;
  name: string;
  kind: FeedSourceKind;
  url: string;
  parserKey?: string;
  enabled: boolean;
  /** Its posts are read as circulars by the parser. Separate from `enabled` — see the request. */
  intoIntake: boolean;
  lastFetchedAt?: string;
  /** Why the last fetch failed; absent once one succeeds. */
  lastError?: string;
  lastNewItems?: number;
  itemCount: number;
}

/**
 * @param intoIntake read this source's posts as circulars, through the parser the mailbox
 *   goes through. Separate from `enabled`: that is whether the page is fetched at all, this
 *   is what is done with what comes back. Worth it for a board of pasted circulars and not
 *   for a trade-press feed, where the extraction model would spend GPU to produce nothing.
 */
export interface FeedSourceRequest {
  name?: string;
  kind: FeedSourceKind;
  url: string;
  parserKey?: string;
  enabled?: boolean;
  intoIntake?: boolean;
}

export interface FeedParser {
  key: string;
  label: string;
  exampleUrl: string;
}

export interface FeedItem {
  id: number;
  sourceId: number;
  sourceName: string;
  publishedAt?: string;
  title?: string;
  text: string;
  url?: string;
  author?: string;
  fetchedAt: string;
}

export interface FeedItemFilter {
  sourceId?: number;
  q?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface FeedTopic {
  id: number;
  name: string;
  keywords: string[];
  selected: boolean;
  sortOrder: number;
}

export interface FeedTopicRequest {
  name: string;
  keywords?: string[];
  selected?: boolean;
  sortOrder?: number;
}

export type FeedSummaryStatus = 'RUNNING' | 'DONE' | 'FAILED';
export type FeedSummaryStrategy = 'SINGLE_PASS' | 'MAP_REDUCE';

export interface FeedSummary {
  id: number;
  runId: string;
  topicId?: number;
  topicName: string;
  status: FeedSummaryStatus;
  content?: string;
  strategy?: FeedSummaryStrategy;
  levels?: number;
  llmCalls?: number;
  itemsConsidered?: number;
  itemsUsed?: number;
  /** Matching items that did not fit under the call cap. */
  itemsDropped?: number;
  promptTokens?: number;
  completionTokens?: number;
  contextWindow?: number;
  periodFrom?: string;
  periodTo?: string;
  /** The prompt as it was when this ran. */
  systemPrompt?: string;
  model?: string;
  error?: string;
  durationMs?: number;
  createdAt: string;
  /** Detail endpoint only. */
  items?: FeedItem[];
}

export interface FeedSettings {
  contextWindowTokens: number;
  summaryMaxTokens: number;
  notesMaxTokens: number;
  lookbackDays: number;
  maxCallsPerTopic: number;
  fetchIntervalMinutes: number;
  systemPrompt: string;
  notesPrompt: string;
  systemPromptCustomised: boolean;
  notesPromptCustomised: boolean;
  defaultContextWindowTokens: number;
  defaultSummaryMaxTokens: number;
  defaultNotesMaxTokens: number;
  defaultLookbackDays: number;
  defaultMaxCallsPerTopic: number;
  defaultFetchIntervalMinutes: number;
  defaultSystemPrompt: string;
  defaultNotesPrompt: string;
  placeholders: string[];
  /** The chat-completions endpoint summaries go to. */
  modelUrl: string;
  modelName: string;
  /** True where a row holds it rather than the environment. */
  modelUrlCustomised: boolean;
  modelNameCustomised: boolean;
  /** FEED_LLM_URL, or the parser's endpoint where that is blank. */
  defaultModelUrl: string;
  defaultModelName: string;
}

export type FeedSettingsRequest = Partial<
  Pick<
    FeedSettings,
    | 'contextWindowTokens'
    | 'summaryMaxTokens'
    | 'notesMaxTokens'
    | 'lookbackDays'
    | 'maxCallsPerTopic'
    | 'fetchIntervalMinutes'
    | 'systemPrompt'
    | 'notesPrompt'
    | 'modelUrl'
    | 'modelName'
  >
>;

export interface FeedStatus {
  analysisEnabled: boolean;
  /** Absent where analysis is off: there is no model there to be unreachable. */
  reachable?: boolean;
  modelUrl?: string;
  sources: number;
  enabledSources: number;
  items: number;
  topics: number;
  selectedTopics: number;
  fetchRunning: boolean;
  lastFetchAt?: string;
  nextFetchAt?: string;
  lastFetchMessage?: string;
  summaryRunning: boolean;
  runTopicIndex?: number;
  runTopicCount?: number;
  runTopicName?: string;
  runStage?: string;
  lastRunMessage?: string;
  lastRunAt?: string;
  warnings: string[];
}

export const feedApi = {
  status: () => client.get<FeedStatus>('/feed/status').then((r) => r.data),

  sources: () => client.get<FeedSource[]>('/feed/sources').then((r) => r.data),
  createSource: (body: FeedSourceRequest) =>
    client.post<FeedSource>('/feed/sources', body).then((r) => r.data),
  updateSource: (id: number, body: FeedSourceRequest) =>
    client.put<FeedSource>(`/feed/sources/${id}`, body).then((r) => r.data),
  deleteSource: (id: number) => client.delete(`/feed/sources/${id}`),
  parsers: () => client.get<FeedParser[]>('/feed/parsers').then((r) => r.data),

  fetchAll: () => client.post<FeedStatus>('/feed/fetch').then((r) => r.data),
  fetchSource: (id: number) =>
    client.post<FeedStatus>(`/feed/sources/${id}/fetch`).then((r) => r.data),

  /** One post, whole — what the Intake screens show beside a reading taken off a board. */
  item: (id: number) => client.get<FeedItem>(`/feed/items/${id}`).then((r) => r.data),

  items: (filter: FeedItemFilter) =>
    client
      .get<PageResponse<FeedItem>>('/feed/items', { params: cleanParams(filter) })
      .then((r) => r.data),

  topics: () => client.get<FeedTopic[]>('/feed/topics').then((r) => r.data),
  createTopic: (body: FeedTopicRequest) =>
    client.post<FeedTopic>('/feed/topics', body).then((r) => r.data),
  updateTopic: (id: number, body: FeedTopicRequest) =>
    client.put<FeedTopic>(`/feed/topics/${id}`, body).then((r) => r.data),
  deleteTopic: (id: number) => client.delete(`/feed/topics/${id}`),
  selectTopics: (selectedIds: number[]) =>
    client.put<FeedTopic[]>('/feed/topics/selection', { selectedIds }).then((r) => r.data),

  summaries: (params: { topicId?: number; page?: number; size?: number }) =>
    client
      .get<PageResponse<FeedSummary>>('/feed/summaries', { params: cleanParams(params) })
      .then((r) => r.data),
  summary: (id: number) => client.get<FeedSummary>(`/feed/summaries/${id}`).then((r) => r.data),
  runSummaries: () => client.post<FeedStatus>('/feed/summaries/run').then((r) => r.data),

  settings: () => client.get<FeedSettings>('/feed/settings').then((r) => r.data),
  updateSettings: (body: FeedSettingsRequest) =>
    client.put<FeedSettings>('/feed/settings', body).then((r) => r.data),
  resetSettings: () => client.delete<FeedSettings>('/feed/settings').then((r) => r.data),
  resetPrompts: () => client.delete<FeedSettings>('/feed/settings/prompts').then((r) => r.data),
  detectContext: () =>
    client
      .get<{ contextWindowTokens: number }>('/feed/settings/detect-context')
      .then((r) => r.data),
};
