import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { askApi } from '../api/ask';

/**
 * Ask the desk queries. The status is mounted by the app shell, not only by the page: it is what
 * decides whether the page is in the navigation at all, the same way the intake and analysis
 * status are.
 */
const KEY = ['ask'] as const;

export const askKeys = {
  all: KEY,
  status: [...KEY, 'status'] as const,
  index: [...KEY, 'index'] as const,
};

/**
 * Whether this deployment has Ask the desk, and with which model.
 *
 * A minute, like intake: it is one cheap call, and the answer changes only when the api restarts,
 * so there is no reason to ask on every navigation.
 */
export const useAskStatus = () =>
  useQuery({
    queryKey: askKeys.status,
    queryFn: askApi.status,
    staleTime: 60_000,
    retry: false,
  });

/**
 * How far the desk's index has got.
 *
 * Polled every two seconds while a run is going and left alone otherwise, the same shape the sweep
 * and the mailbox sync use. An index run is minutes of the embedding server, so the figures have to
 * move while somebody watches them; when nothing is running there is nothing to watch.
 */
export const useAskIndex = (enabled: boolean) =>
  useQuery({
    queryKey: askKeys.index,
    queryFn: askApi.indexStatus,
    enabled,
    refetchInterval: (q) => (q.state.data?.running ? 2_000 : false),
  });

export function useStartAskIndex() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: askApi.startIndex,
    // The response is the status at the moment the run began, so the card can show it
    // straight away and the refetch then picks up the progress.
    onSuccess: (status) => {
      qc.setQueryData(askKeys.index, status);
      qc.invalidateQueries({ queryKey: askKeys.index });
    },
  });
}
