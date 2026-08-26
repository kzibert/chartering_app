import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { analysisApi } from '../api/analysis';
import type {
  AnalysisCaptureRequest,
  AnalysisPasteRequest,
  AnalysisSampleFilter,
  AnalysisSampleUpdateRequest,
} from '../api/analysis';

/**
 * Analysis queries, in one place for the same reason the mailbox has one: the counters at
 * the top of the tab, the list under them and the sample open in the drawer are three views
 * of one corpus, and they only stay in step if a write from any of them invalidates all of
 * it. Labelling one email moves a number in the header — that number moving is half of what
 * tells the user the save worked.
 */
const KEY = ['analysis'] as const;

export const analysisKeys = {
  all: KEY,
  status: [...KEY, 'status'] as const,
  samples: (filter: AnalysisSampleFilter) => [...KEY, 'samples', filter] as const,
  sample: (id: number) => [...KEY, 'sample', id] as const,
};

export function useAnalysisInvalidator() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: KEY });
}

/**
 * Whether this deployment has the workbench at all, and how the corpus stands.
 *
 * Mounted by the app shell, not only by the tab: it is what decides whether the tab is in
 * the navigation. `staleTime: Infinity` because ANALYSIS_ENABLED cannot change without the
 * API restarting — but the counters live on the same response, so every mutation below
 * invalidates the whole prefix and pulls fresh ones.
 */
export const useAnalysisStatus = () =>
  useQuery({
    queryKey: analysisKeys.status,
    queryFn: analysisApi.status,
    staleTime: Infinity,
    // A deployment without the feature answers enabled:false rather than failing, so a
    // retry here would only ever be retrying a real outage — which the rest of the app is
    // already telling the user about.
    retry: false,
  });

export const useAnalysisSamples = (filter: AnalysisSampleFilter, enabled: boolean) =>
  useQuery({
    queryKey: analysisKeys.samples(filter),
    queryFn: () => analysisApi.search(filter),
    enabled,
    // Paging and filtering both replace the whole result set; keeping the previous page on
    // screen while the next loads stops the list flickering to empty on every keystroke.
    placeholderData: (prev) => prev,
  });

export const useAnalysisSample = (id?: number) =>
  useQuery({
    queryKey: analysisKeys.sample(id ?? 0),
    queryFn: () => analysisApi.get(id!),
    enabled: id != null,
  });

export function useAnalysisMutations() {
  const invalidate = useAnalysisInvalidator();

  const update = useMutation({
    mutationFn: (v: { id: number; body: AnalysisSampleUpdateRequest }) =>
      analysisApi.update(v.id, v.body),
    onSuccess: invalidate,
  });

  const remove = useMutation({
    mutationFn: (id: number) => analysisApi.remove(id),
    onSuccess: invalidate,
  });

  const capture = useMutation({
    mutationFn: (body: AnalysisCaptureRequest) => analysisApi.capture(body),
    onSuccess: invalidate,
  });

  const paste = useMutation({
    mutationFn: (body: AnalysisPasteRequest) => analysisApi.paste(body),
    onSuccess: invalidate,
  });

  /**
   * The download. A mutation rather than a query because it is an action with a side effect
   * outside React — a file lands in the user's downloads — and nothing on screen is caching
   * its result.
   */
  const exportJsonl = useMutation({ mutationFn: analysisApi.exportJsonl });

  return { update, remove, capture, paste, exportJsonl };
}
