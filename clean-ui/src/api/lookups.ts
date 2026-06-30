import { client } from './client';
import type { LookupResponse } from './types';

export const lookupsApi = {
  vesselTypes: () => client.get<string[]>('/lookups/vessel-types').then((r) => r.data),
  flags: () => client.get<string[]>('/lookups/flags').then((r) => r.data),
  regions: () => client.get<LookupResponse[]>('/lookups/regions').then((r) => r.data),
  ports: () => client.get<LookupResponse[]>('/lookups/ports').then((r) => r.data),
  tonnageCategories: () =>
    client.get<LookupResponse[]>('/lookups/tonnage-categories').then((r) => r.data),
};
