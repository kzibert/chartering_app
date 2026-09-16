import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { feedApi } from '../api/feed';
import type {
  FeedItemFilter,
  FeedSettingsRequest,
  FeedSourceRequest,
  FeedTopicRequest,
} from '../api/feed';

/**
 * Feed queries in one place, for the reason Intake keeps its own: the header counts, the sources
 * list and the summaries are views of one state, and a write from any of them invalidates it all.
 */
const KEY = ['feed'] as const;

export const feedKeys = {
  all: KEY,
  status: [...KEY, 'status'] as const,
  sources: [...KEY, 'sources'] as const,
  parsers: [...KEY, 'parsers'] as const,
  items: (filter: FeedItemFilter) => [...KEY, 'items', filter] as const,
  topics: [...KEY, 'topics'] as const,
  summaries: (topicId?: number, page?: number) => [...KEY, 'summaries', topicId, page] as const,
  summary: (id: number) => [...KEY, 'summary', id] as const,
  settings: [...KEY, 'settings'] as const,
};

/**
 * What is running and whether this deployment can fetch and summarise.
 *
 * Polled every three seconds while a fetch or a summary run is in flight — the stage line
 * ("reading batch 2 of 4") is the only sign a minutes-long run is alive — and left alone otherwise.
 */
export const useFeedStatus = () =>
  useQuery({
    queryKey: feedKeys.status,
    queryFn: feedApi.status,
    staleTime: 30_000,
    retry: false,
    refetchInterval: (q) =>
      q.state.data?.fetchRunning || q.state.data?.summaryRunning ? 3_000 : false,
  });

export const useFeedSources = () =>
  useQuery({ queryKey: feedKeys.sources, queryFn: feedApi.sources });

/** From a list compiled into the API; it cannot change without a deploy. */
export const useFeedParsers = () =>
  useQuery({ queryKey: feedKeys.parsers, queryFn: feedApi.parsers, staleTime: Infinity });

export const useFeedItems = (filter: FeedItemFilter) =>
  useQuery({
    queryKey: feedKeys.items(filter),
    queryFn: () => feedApi.items(filter),
    placeholderData: (prev) => prev,
  });

export const useFeedTopics = () =>
  useQuery({ queryKey: feedKeys.topics, queryFn: feedApi.topics });

export const useFeedSummaries = (params: { topicId?: number; page?: number; size?: number }) =>
  useQuery({
    queryKey: feedKeys.summaries(params.topicId, params.page),
    queryFn: () => feedApi.summaries(params),
    placeholderData: (prev) => prev,
  });

export const useFeedSummary = (id?: number) =>
  useQuery({
    queryKey: feedKeys.summary(id ?? 0),
    queryFn: () => feedApi.summary(id!),
    enabled: id != null,
  });

export const useFeedSettings = () =>
  useQuery({ queryKey: feedKeys.settings, queryFn: feedApi.settings });

export function useFeedInvalidator() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: KEY });
}

export function useFeedMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: KEY });
  const m = <A, R>(fn: (a: A) => Promise<R>) => useMutation({ mutationFn: fn, onSuccess: invalidate });

  return {
    createSource: m((body: FeedSourceRequest) => feedApi.createSource(body)),
    updateSource: m((v: { id: number; body: FeedSourceRequest }) => feedApi.updateSource(v.id, v.body)),
    deleteSource: m((id: number) => feedApi.deleteSource(id)),
    fetchAll: m((_: void) => feedApi.fetchAll()),
    fetchSource: m((id: number) => feedApi.fetchSource(id)),
    createTopic: m((body: FeedTopicRequest) => feedApi.createTopic(body)),
    updateTopic: m((v: { id: number; body: FeedTopicRequest }) => feedApi.updateTopic(v.id, v.body)),
    deleteTopic: m((id: number) => feedApi.deleteTopic(id)),
    selectTopics: m((ids: number[]) => feedApi.selectTopics(ids)),
    runSummaries: m((_: void) => feedApi.runSummaries()),
    updateSettings: m((body: FeedSettingsRequest) => feedApi.updateSettings(body)),
    resetSettings: m((_: void) => feedApi.resetSettings()),
    resetPrompts: m((_: void) => feedApi.resetPrompts()),
  };
}
