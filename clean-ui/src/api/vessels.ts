import { client, cleanParams } from './client';
import type {
  ConfirmRequest,
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

  create: (body: VesselRequest) =>
    client.post<VesselResponse>('/vessels', body).then((r) => r.data),

  update: (id: number, body: VesselRequest) =>
    client.put<VesselResponse>(`/vessels/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/vessels/${id}`).then((r) => r.data),

  confirm: (id: number, confirmed: boolean, body?: ConfirmRequest) =>
    client
      .patch<VesselResponse>(`/vessels/${id}/confirm`, body ?? {}, { params: { confirmed } })
      .then((r) => r.data),
};
