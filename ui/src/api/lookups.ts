import { client } from './client';
import type { LookupResponse, PortLookupResponse, TradeAreaResponse } from './types';

export const lookupsApi = {
  vesselTypes: () => client.get<string[]>('/lookups/vessel-types').then((r) => r.data),
  flags: () => client.get<string[]>('/lookups/flags').then((r) => r.data),
  regions: () => client.get<LookupResponse[]>('/lookups/regions').then((r) => r.data),
  /** Ports carry the trade area they sit on; a few have none yet and say so by omitting it. */
  ports: () => client.get<PortLookupResponse[]>('/lookups/ports').then((r) => r.data),
  tradeAreas: () =>
    client.get<TradeAreaResponse[]>('/lookups/trade-areas').then((r) => r.data),
  tonnageCategories: () =>
    client.get<LookupResponse[]>('/lookups/tonnage-categories').then((r) => r.data),
};
