import { client, cleanParams } from './client';
import type {
  ConfirmRequest,
  ContactResponse,
  PageResponse,
  VesselDetailResponse,
  VesselFilter,
  VesselRequest,
  VesselResponse,
} from './types';

export const vesselsApi = {
  search: (filter: VesselFilter) =>
    client
      .get<PageResponse<VesselResponse>>('/vessels', { params: cleanParams(filter) })
      .then((r) => r.data),

  get: (id: number) =>
    client.get<VesselDetailResponse>(`/vessels/${id}`).then((r) => r.data),

  // Email contacts of the owner companies of every vessel matching the filter.
  // mainOnly collapses each owner to one address: its main email, else its first.
  ownerEmailContacts: (
    filter: Partial<VesselFilter>,
    confirmedOnly: boolean,
    mainOnly = false,
  ) =>
    client
      .get<ContactResponse[]>('/vessels/contacts', {
        params: cleanParams({ ...filter, confirmedOnly, mainOnly }),
      })
      .then((r) => r.data),

  create: (body: VesselRequest) =>
    client.post<VesselResponse>('/vessels', body).then((r) => r.data),

  update: (id: number, body: VesselRequest) =>
    client.put<VesselResponse>(`/vessels/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/vessels/${id}`).then((r) => r.data),

  confirm: (id: number, confirmed: boolean, body?: ConfirmRequest) =>
    client
      .patch<VesselResponse>(`/vessels/${id}/confirm`, body ?? {}, { params: { confirmed } })
      .then((r) => r.data),

  setBanned: (id: number, banned: boolean) =>
    client
      .patch<VesselResponse>(`/vessels/${id}/ban`, {}, { params: { banned } })
      .then((r) => r.data),
};
