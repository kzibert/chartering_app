import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { vesselsApi } from './vessels';
import { companiesApi } from './companies';
import { peopleApi } from './people';
import { contactsApi } from './contacts';
import { lookupsApi } from './lookups';
import type {
  CompanyFilter,
  CompanyRequest,
  ConfirmRequest,
  ContactFilter,
  ContactRequest,
  PersonRequest,
  VesselFilter,
  VesselRequest,
} from './types';

// Invalidate list/detail caches plus dashboard counts after any write.
function useInvalidator() {
  const qc = useQueryClient();
  return (...keys: string[]) => {
    keys.forEach((k) => qc.invalidateQueries({ queryKey: [k] }));
    qc.invalidateQueries({ queryKey: ['dashboard'] });
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

/* ---------------- vessels ---------------- */
export const useVessels = (filter: VesselFilter) =>
  useQuery({ queryKey: ['vessels', filter], queryFn: () => vesselsApi.search(filter) });

export const useVessel = (id?: number) =>
  useQuery({ queryKey: ['vessel', id], queryFn: () => vesselsApi.get(id!), enabled: id != null });

export function useVesselMutations() {
  const invalidate = useInvalidator();
  const create = useMutation({
    mutationFn: (body: VesselRequest) => vesselsApi.create(body),
    onSuccess: () => invalidate('vessels'),
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: VesselRequest }) => vesselsApi.update(v.id, v.body),
    onSuccess: () => invalidate('vessels', 'vessel'),
  });
  const remove = useMutation({
    mutationFn: (id: number) => vesselsApi.remove(id),
    onSuccess: () => invalidate('vessels'),
  });
  const confirm = useMutation({
    mutationFn: (v: { id: number; confirmed: boolean; body?: ConfirmRequest }) =>
      vesselsApi.confirm(v.id, v.confirmed, v.body),
    onSuccess: () => invalidate('vessels', 'vessel'),
  });
  return { create, update, remove, confirm };
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
    onSuccess: () => invalidate('companies'),
  });
  const confirm = useMutation({
    mutationFn: (v: { id: number; confirmed: boolean; body?: ConfirmRequest }) =>
      companiesApi.confirm(v.id, v.confirmed, v.body),
    onSuccess: () => invalidate('companies', 'company'),
  });
  return { create, update, remove, confirm };
}

/* ---------------- people ---------------- */
export const usePeople = (companyId?: number) =>
  useQuery({ queryKey: ['people', companyId ?? null], queryFn: () => peopleApi.list(companyId) });

export function usePersonMutations() {
  const invalidate = useInvalidator();
  const create = useMutation({
    mutationFn: (body: PersonRequest) => peopleApi.create(body),
    onSuccess: () => invalidate('people'),
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: PersonRequest }) => peopleApi.update(v.id, v.body),
    onSuccess: () => invalidate('people'),
  });
  const remove = useMutation({
    mutationFn: (id: number) => peopleApi.remove(id),
    onSuccess: () => invalidate('people'),
  });
  return { create, update, remove };
}

/* ---------------- contacts ---------------- */
export const useContacts = (filter: ContactFilter) =>
  useQuery({ queryKey: ['contacts', filter], queryFn: () => contactsApi.search(filter) });

export function useContactMutations() {
  const invalidate = useInvalidator();
  const create = useMutation({
    mutationFn: (body: ContactRequest) => contactsApi.create(body),
    onSuccess: () => invalidate('contacts'),
  });
  const update = useMutation({
    mutationFn: (v: { id: number; body: ContactRequest }) => contactsApi.update(v.id, v.body),
    onSuccess: () => invalidate('contacts'),
  });
  const remove = useMutation({
    mutationFn: (id: number) => contactsApi.remove(id),
    onSuccess: () => invalidate('contacts'),
  });
  const confirm = useMutation({
    mutationFn: (v: { id: number; confirmed: boolean; body?: ConfirmRequest }) =>
      contactsApi.confirm(v.id, v.confirmed, v.body),
    onSuccess: () => invalidate('contacts'),
  });
  return { create, update, remove, confirm };
}
