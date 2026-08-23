import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toVesselRequest, vesselsApi } from './vessels';
import { companiesApi } from './companies';
import { peopleApi } from './people';
import { contactsApi } from './contacts';
import { lookupsApi } from './lookups';
import { settingsApi } from './settings';
import { forgetRecent, type RecentKind } from '../recent/store';
import type {
  CompanyFilter,
  CompanyRequest,
  ConfirmRequest,
  ContactRequest,
  PeopleFilter,
  PersonRequest,
  VesselCompanyRole,
  VesselFilter,
  VesselRequest,
  VesselResponse,
} from './types';

// Invalidate list/detail caches plus dashboard counts after any write.
function useInvalidator() {
  const qc = useQueryClient();
  return (...keys: string[]) => {
    keys.forEach((k) => qc.invalidateQueries({ queryKey: [k] }));
    qc.invalidateQueries({ queryKey: ['dashboard'] });
  };
}

/**
 * Mark a deleted record's detail query as gone, by writing null over it.
 *
 * The two obvious moves both end up fetching the dead id, and both were measured doing it:
 *
 * - **invalidate** refetches every matching *active* query at once, and a drawer still
 *   mounted on the record is exactly such a query;
 * - **removeQueries** leaves that same mounted observer holding nothing, so it fetches for
 *   itself — the removal happens before React has flushed the state change that would have
 *   unmounted it.
 *
 * Either way the delete's own cleanup asks the server for the id it has just deleted, and
 * the 404 lands in the notification tray while the user is still looking at the
 * confirmation popup. Writing null is what stops it: the observer *has* data, so it has no
 * reason to fetch, and the drawer falls through to the empty branch it already has for a
 * record that will not load.
 *
 * It also works when nothing clears the id at all — deleting a person from a row on the
 * People tab while their drawer sits open behind it — which is why the fix lives here
 * rather than in a rule about closing drawers first. The entry is garbage-collected on the
 * normal timer once nothing is observing it.
 *
 * The dashboard's "recently opened" trail is pruned at the same time, and for the same
 * reason: it is the one place that still holds a link to a record after it has gone from
 * every list, and following that link would otherwise land on the tombstone and sit there
 * loading forever. The three cache keys are named to match RecentKind exactly so this
 * cannot be wired up wrong.
 */
function useMarkDeleted() {
  const qc = useQueryClient();
  return (key: RecentKind, id: number) => {
    qc.setQueryData([key, id], null);
    forgetRecent(key, id);
  };
}

/* ---------------- lookups (long-lived) ---------------- */
const LOOKUP_OPTS = { staleTime: 1000 * 60 * 30 };
export const useVesselTypes = () =>
  useQuery({ queryKey: ['lookups', 'vessel-types'], queryFn: lookupsApi.vesselTypes, ...LOOKUP_OPTS });
export const useFlags = () =>
  useQuery({ queryKey: ['lookups', 'flags'], queryFn: lookupsApi.flags, ...LOOKUP_OPTS });
export const useRegions = () =>
  useQuery({ queryKey: ['lookups', 'regions'], queryFn: lookupsApi.regions, ...LOOKUP_OPTS });
export const usePorts = () =>
  useQuery({ queryKey: ['lookups', 'ports'], queryFn: lookupsApi.ports, ...LOOKUP_OPTS });
export const useTonnageCategories = () =>
  useQuery({ queryKey: ['lookups', 'tonnage'], queryFn: lookupsApi.tonnageCategories, ...LOOKUP_OPTS });

/**
 * The greeting prefilled into WhatsApp links. Long-lived and shared: every phone row on a
 * page asks for it, and one cached answer keeps them all quoting the same message.
 */
export const useWhatsappSettings = () =>
  useQuery({ queryKey: ['settings', 'whatsapp'], queryFn: settingsApi.whatsapp, ...LOOKUP_OPTS });

/* ---------------- vessels ---------------- */
export const useVessels = (filter: VesselFilter) =>
  useQuery({ queryKey: ['vessels', filter], queryFn: () => vesselsApi.search(filter) });

export const useVessel = (id?: number) =>
  useQuery({ queryKey: ['vessel', id], queryFn: () => vesselsApi.get(id!), enabled: id != null });

export function useVesselMutations() {
  const invalidate = useInvalidator();
  const markDeleted = useMarkDeleted();
  // 'company' is included because the company drawer lists a company's fleet from
  // ['company', id, 'vessels'] — writes made there (or any owner change) would
  // otherwise leave that list stale.
  const touched = ['vessels', 'vessel', 'company'] as const;
  const create = useMutation({
    mutationFn: (body: VesselRequest) => vesselsApi.create(body),
    onSuccess: () => invalidate(...touched),
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: VesselRequest }) => vesselsApi.update(v.id, v.body),
    onSuccess: () => invalidate(...touched),
  });
  const remove = useMutation({
    mutationFn: (id: number) => vesselsApi.remove(id),
    // Not `touched`: that includes 'vessel', which would refetch the vessel just deleted.
    onSuccess: (_deleted, id) => {
      markDeleted('vessel', id);
      invalidate('vessels', 'company');
    },
  });
  const confirm = useMutation({
    mutationFn: (v: { id: number; confirmed: boolean; body?: ConfirmRequest }) =>
      vesselsApi.confirm(v.id, v.confirmed, v.body),
    onSuccess: () => invalidate('vessels', 'vessel'),
  });
  const ban = useMutation({
    mutationFn: (v: { id: number; banned: boolean }) => vesselsApi.setBanned(v.id, v.banned),
    onSuccess: () => invalidate('vessels', 'vessel', 'company'),
  });
  // Attach a company to a vessel as owner / exclusive broker / broker. Touches
  // 'company' too: the change shows up in that company's Vessels tab as well.
  const setLink = useMutation({
    mutationFn: (v: { vesselId: number; companyId: number; role: VesselCompanyRole }) =>
      vesselsApi.setLink(v.vesselId, v.companyId, v.role),
    onSuccess: () => invalidate(...touched),
  });
  const removeLink = useMutation({
    mutationFn: (v: { vesselId: number; companyId: number }) =>
      vesselsApi.removeLink(v.vesselId, v.companyId),
    onSuccess: () => invalidate(...touched),
  });
  // Link/reassign/unassign a vessel's owner without opening the full edit form.
  // There is no owner-only endpoint, so the untouched fields ride along from the
  // vessel we already hold. ownerId undefined = leave the vessel unassigned.
  const setOwner = useMutation({
    mutationFn: (v: { vessel: VesselResponse; ownerId?: number }) =>
      vesselsApi.update(v.vessel.id, { ...toVesselRequest(v.vessel), ownerId: v.ownerId }),
    onSuccess: () => invalidate(...touched),
  });
  return { create, update, remove, confirm, ban, setOwner, setLink, removeLink };
}

/* ---------------- companies ---------------- */
export const useCompanies = (filter: CompanyFilter) =>
  useQuery({ queryKey: ['companies', filter], queryFn: () => companiesApi.search(filter) });

export const useCompany = (id?: number) =>
  useQuery({ queryKey: ['company', id], queryFn: () => companiesApi.get(id!), enabled: id != null });

export const useCompanyContacts = (id?: number) =>
  useQuery({
    queryKey: ['company', id, 'contacts'],
    queryFn: () => companiesApi.contacts(id!),
    enabled: id != null,
  });

export const useCompanyVessels = (id?: number) =>
  useQuery({
    queryKey: ['company', id, 'vessels'],
    queryFn: () => companiesApi.vessels(id!),
    enabled: id != null,
  });

export function useCompanyMutations() {
  const invalidate = useInvalidator();
  const markDeleted = useMarkDeleted();
  const create = useMutation({
    mutationFn: (body: CompanyRequest) => companiesApi.create(body),
    onSuccess: () => invalidate('companies'),
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: CompanyRequest }) => companiesApi.update(v.id, v.body),
    onSuccess: () => invalidate('companies', 'company'),
  });
  const remove = useMutation({
    mutationFn: (id: number) => companiesApi.remove(id),
    /*
     * Deleting a company takes its people and their contacts with it — people.company_id
     * and contacts.company_id are both ON DELETE CASCADE — and leaves its vessels behind
     * with a null owner. Invalidating 'companies' alone was never enough: the People tab
     * would go on listing people who no longer exist. 'company' is deliberately absent
     * from the list, because markDeleted() has just written this one off and invalidating
     * the prefix would fetch it straight back.
     */
    onSuccess: (_deleted, id) => {
      markDeleted('company', id);
      invalidate('companies', 'people', 'contacts', 'vessels', 'vessel');
    },
  });
  const confirm = useMutation({
    mutationFn: (v: { id: number; confirmed: boolean; body?: ConfirmRequest }) =>
      companiesApi.confirm(v.id, v.confirmed, v.body),
    onSuccess: () => invalidate('companies', 'company'),
  });
  const ban = useMutation({
    mutationFn: (v: { id: number; banned: boolean }) => companiesApi.setBanned(v.id, v.banned),
    onSuccess: () => invalidate('companies', 'company'),
  });
  return { create, update, remove, confirm, ban };
}

/* ---------------- people ---------------- */
export const usePeople = (companyId?: number, name?: string) =>
  useQuery({
    queryKey: ['people', companyId ?? null, name ?? null],
    queryFn: () => peopleApi.list(companyId, name),
  });

export const usePeopleSearch = (filter: PeopleFilter) =>
  useQuery({ queryKey: ['people', 'search', filter], queryFn: () => peopleApi.search(filter) });

export const usePerson = (id?: number) =>
  useQuery({ queryKey: ['person', id], queryFn: () => peopleApi.get(id!), enabled: id != null });

/** A person's own emails/phones — powers the person drawer. */
export const usePersonContacts = (personId?: number) =>
  useQuery({
    // Keyed under 'contacts' so the contact mutations' invalidation reaches it.
    queryKey: ['contacts', 'of-person', personId],
    // One person never has enough contacts to page; size keeps it to a single request.
    queryFn: () => contactsApi.search({ personId, size: 200 }),
    enabled: personId != null,
  });

export function usePersonMutations() {
  const invalidate = useInvalidator();
  const markDeleted = useMarkDeleted();
  // Contact rows embed the person's name/greeting, and the company + vessel drawers
  // render those rows from their own query keys — so a person edit has to invalidate
  // more than 'people' or the drawer keeps showing the old name.
  const touched = ['people', 'person', 'contacts', 'company', 'vessel'] as const;
  const create = useMutation({
    mutationFn: (body: PersonRequest) => peopleApi.create(body),
    onSuccess: () => invalidate(...touched),
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: PersonRequest }) => peopleApi.update(v.id, v.body),
    onSuccess: () => invalidate(...touched),
  });
  const remove = useMutation({
    mutationFn: (id: number) => peopleApi.remove(id),
    // Not `touched`: that includes 'person', which would refetch the person just deleted
    // and 404 into the notification tray while their drawer is still closing.
    onSuccess: (_deleted, id) => {
      markDeleted('person', id);
      invalidate('people', 'contacts', 'company', 'vessel');
    },
  });
  // Also invalidates the circulation lists: flagging somebody changes which rows a list
  // will actually send to, and the lists page reads that off the server.
  const setHasLeft = useMutation({
    mutationFn: (v: { id: number; hasLeft: boolean }) => peopleApi.setHasLeft(v.id, v.hasLeft),
    onSuccess: () => invalidate(...touched, 'circulation-lists'),
  });
  return { create, update, remove, setHasLeft };
}

/* ---------------- contacts ---------------- */
// No flat contact list hook: contacts are reached through their person
// (usePeopleSearch / usePersonContacts) since the Contacts tab was merged into People.

export function useContactMutations() {
  const invalidate = useInvalidator();
  // The company drawer reads contacts from ['company', id, 'contacts'] and the vessel
  // drawer from ['vessel', id] — neither is matched by invalidating 'contacts' alone, so
  // an edit made inside a drawer would leave the drawer showing the old value. 'companies'
  // is included because adding/removing an email can flip the no-working-email label.
  const touched = ['contacts', 'company', 'companies', 'vessel'] as const;
  const create = useMutation({
    mutationFn: (body: ContactRequest) => contactsApi.create(body),
    onSuccess: () => invalidate(...touched),
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: ContactRequest }) => contactsApi.update(v.id, v.body),
    onSuccess: () => invalidate(...touched),
  });
  const remove = useMutation({
    mutationFn: (id: number) => contactsApi.remove(id),
    onSuccess: () => invalidate(...touched),
  });
  // Moving a contact between people also changes who the People tab groups it under, and
  // 'people' is not in `touched` — a move without this leaves the row under its old owner.
  const assign = useMutation({
    mutationFn: (v: { id: number; personId?: number; companyId?: number }) =>
      contactsApi.assign(v.id, v.personId, v.companyId),
    onSuccess: () => invalidate(...touched, 'people', 'person'),
  });
  const confirm = useMutation({
    mutationFn: (v: { id: number; confirmed: boolean; body?: ConfirmRequest }) =>
      contactsApi.confirm(v.id, v.confirmed, v.body),
    onSuccess: () => invalidate('contacts', 'company', 'vessel'),
  });
  const ban = useMutation({
    mutationFn: (v: { id: number; banned: boolean }) => contactsApi.setBanned(v.id, v.banned),
    onSuccess: () => invalidate('contacts', 'company', 'vessel'),
  });
  // Promoting demotes the company's previous main, so the whole company must be refetched.
  const setMain = useMutation({
    mutationFn: (v: { id: number; main: boolean }) => contactsApi.setMain(v.id, v.main),
    onSuccess: () => invalidate('contacts', 'company', 'vessel'),
  });
  // Nothing is demoted, so only the contact's own views need refreshing.
  const setCirc = useMutation({
    mutationFn: (v: { id: number; circ: boolean }) => contactsApi.setCirc(v.id, v.circ),
    onSuccess: () => invalidate('contacts', 'company', 'vessel'),
  });
  // Setting this clears circ on the server, so the contact's own views must refetch to
  // pick up both flags rather than only the one that was clicked.
  const setNoCirc = useMutation({
    mutationFn: (v: { id: number; noCirc: boolean }) => contactsApi.setNoCirc(v.id, v.noCirc),
    onSuccess: () => invalidate('contacts', 'company', 'vessel'),
  });
  // Flipping this can change the company's "no working email" label, so refresh companies too.
  const setWorking = useMutation({
    mutationFn: (v: { id: number; working: boolean }) => contactsApi.setWorking(v.id, v.working),
    onSuccess: () => invalidate(...touched),
  });
  // Affects nothing but the row itself — no list is collected by WhatsApp. 'people' is in
  // the list because the People tab renders its expanded contact rows straight out of the
  // people search response, so invalidating 'contacts' alone leaves the button it was just
  // clicked on still showing the old state.
  const setHasWhatsapp = useMutation({
    mutationFn: (v: { id: number; hasWhatsapp: boolean }) =>
      contactsApi.setHasWhatsapp(v.id, v.hasWhatsapp),
    onSuccess: () => invalidate('contacts', 'company', 'vessel', 'people'),
  });
  return {
    create, update, remove, assign, confirm, ban,
    setMain, setCirc, setNoCirc, setWorking, setHasWhatsapp,
  };
}
