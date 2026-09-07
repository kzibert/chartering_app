import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { intakeApi } from '../api/intake';
import type {
  ApplyLookupRequest,
  IntakeItemFilter,
  IntakeResolveRequest,
  LinkSenderRequest,
  ParseStatus,
  ParserSettingsRequest,
} from '../api/intake';

/**
 * Intake queries, in one place for the reason the mailbox and the analysis workbench have
 * one: the counters in the header, the queue under them and the item open in the drawer are
 * three views of one state, and they only stay in step if a write from any of them
 * invalidates all of it. Accepting a disagreement moves a number in the header — that number
 * moving is half of what tells the user it worked.
 */
const KEY = ['intake'] as const;

export const intakeKeys = {
  all: KEY,
  status: [...KEY, 'status'] as const,
  items: (filter: IntakeItemFilter) => [...KEY, 'items', filter] as const,
  item: (id: number) => [...KEY, 'item', id] as const,
  parsed: (status?: ParseStatus, page?: number) => [...KEY, 'parsed', status, page] as const,
  parsedDetail: (id: number) => [...KEY, 'parsed', 'detail', id] as const,
  sweep: [...KEY, 'sweep'] as const,
  settings: [...KEY, 'settings'] as const,
  vesselFields: [...KEY, 'vessel-fields'] as const,
  cargoSources: (cargoId: number) => [...KEY, 'cargo-sources', cargoId] as const,
};

export function useIntakeInvalidator() {
  const qc = useQueryClient();
  return () => qc.invalidateQueries({ queryKey: KEY });
}

/**
 * Whether this deployment reads its mail with a model, and how that is going.
 *
 * Mounted by the app shell, not only by the tab: it is what decides whether the tab is in
 * the navigation. Unlike the analysis one this is NOT `staleTime: Infinity` — the response
 * carries whether the model server is answering and how many messages are waiting, and both
 * change while somebody is looking at the screen. PARSER_ENABLED cannot change without a
 * restart, but it is the cheapest field on a response that has to be fresh anyway.
 */
export const useIntakeStatus = () =>
  useQuery({
    queryKey: intakeKeys.status,
    queryFn: intakeApi.status,
    // A minute: long enough that the shell's own render does not reach the model server on
    // every navigation, short enough that starting the container is noticed without a
    // reload.
    staleTime: 60_000,
    // A deployment without the feature answers enabled:false rather than failing, so a retry
    // here would only ever be retrying a real outage — which the rest of the app already
    // reports.
    retry: false,
  });

export const useIntakeItems = (filter: IntakeItemFilter, enabled: boolean) =>
  useQuery({
    queryKey: intakeKeys.items(filter),
    queryFn: () => intakeApi.items(filter),
    enabled,
    // Paging and filtering replace the whole result set; keeping the previous page on screen
    // while the next loads stops the queue flickering to empty on every click.
    placeholderData: (prev) => prev,
  });

export const useIntakeItem = (id?: number) =>
  useQuery({
    queryKey: intakeKeys.item(id ?? 0),
    queryFn: () => intakeApi.item(id!),
    enabled: id != null,
  });

export const useParsedEmails = (
  params: { status?: ParseStatus; page?: number; size?: number },
  enabled: boolean,
) =>
  useQuery({
    queryKey: intakeKeys.parsed(params.status, params.page),
    queryFn: () => intakeApi.parsed(params),
    enabled,
    placeholderData: (prev) => prev,
  });

export const useParsedEmail = (id?: number) =>
  useQuery({
    queryKey: intakeKeys.parsedDetail(id ?? 0),
    queryFn: () => intakeApi.parsedDetail(id!),
    enabled: id != null,
  });

/**
 * Whether a sweep is running, and what the last one did.
 *
 * Polled every three seconds while one is in flight and left alone otherwise — a sweep is
 * minutes of somebody else's GPU, so the screen has to watch it, but there is nothing to
 * watch when nothing is running. `refetchInterval` taking a function is what lets one query
 * do both.
 */
export const useSweep = (enabled: boolean) =>
  useQuery({
    queryKey: intakeKeys.sweep,
    queryFn: intakeApi.sweep,
    enabled,
    refetchInterval: (query) => (query.state.data?.running ? 3_000 : false),
  });

export const useParserSettings = (enabled: boolean) =>
  useQuery({
    queryKey: intakeKeys.settings,
    queryFn: intakeApi.settings,
    enabled,
  });

/**
 * The labels the vessel's own edit form uses.
 *
 * `staleTime: Infinity` because it is derived from a constant list in the API and cannot
 * change without a deploy — unlike everything else here.
 */
export const useVesselFieldLabels = (enabled: boolean) =>
  useQuery({
    queryKey: intakeKeys.vesselFields,
    queryFn: intakeApi.vesselFields,
    enabled,
    staleTime: Infinity,
  });

/** Who has told us about one cargo. Read on the cargo's own drawer, not only here. */
export const useCargoSources = (cargoId?: number) =>
  useQuery({
    queryKey: intakeKeys.cargoSources(cargoId ?? 0),
    queryFn: () => intakeApi.cargoSources(cargoId!),
    enabled: cargoId != null,
  });

export function useIntakeMutations() {
  const qc = useQueryClient();
  const invalidate = () => qc.invalidateQueries({ queryKey: KEY });

  /**
   * Answering an item invalidates far more than this feature.
   *
   * Accepting writes to a vessel, a cargo or a position, so the Vessels, Cargoes, Open Fleet
   * and Match tabs are all stale the moment it returns. Clearing everything is the honest
   * thing to do and costs a refetch of whatever happens to be mounted — which is one screen.
   */
  const resolve = useMutation({
    mutationFn: (v: { id: number; body: IntakeResolveRequest }) =>
      intakeApi.resolve(v.id, v.body),
    onSuccess: () => qc.invalidateQueries(),
  });

  const startSweep = useMutation({
    mutationFn: intakeApi.startSweep,
    onSuccess: invalidate,
  });

  /**
   * Search an outside source for this hull, now.
   *
   * Invalidates only this feature: a lookup writes nothing to a vessel, it stores a proposal.
   * The wholesale clear below is for the calls that do write.
   */
  const lookup = useMutation({
    mutationFn: (id: number) => intakeApi.lookup(id),
    onSuccess: invalidate,
  });

  /**
   * Take the ticked figures from a lookup onto the vessel.
   *
   * Clears everything, like resolving: this writes to a vessel, so the Vessels, Open fleet
   * and Match tabs are all stale the moment it returns.
   */
  const applyLookup = useMutation({
    mutationFn: (v: { id: number; body: ApplyLookupRequest }) =>
      intakeApi.applyLookup(v.id, v.body),
    onSuccess: () => qc.invalidateQueries(),
  });

  /** Attach the sender's company to the vessel. Also a write, also clears everything. */
  const linkSender = useMutation({
    mutationFn: (v: { id: number; body: LinkSenderRequest }) =>
      intakeApi.linkSender(v.id, v.body),
    onSuccess: () => qc.invalidateQueries(),
  });

  const reopen = useMutation({
    mutationFn: (mailMessageId: number) => intakeApi.reopen(mailMessageId),
    onSuccess: invalidate,
  });

  const updateSettings = useMutation({
    mutationFn: (body: ParserSettingsRequest) => intakeApi.updateSettings(body),
    onSuccess: invalidate,
  });

  const resetSettings = useMutation({
    mutationFn: intakeApi.resetSettings,
    onSuccess: invalidate,
  });

  return { resolve, startSweep, reopen, updateSettings, resetSettings, lookup, applyLookup, linkSender };
}
