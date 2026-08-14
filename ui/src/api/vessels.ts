import { client, cleanParams } from './client';
import type {
  ConfirmRequest,
  ContactResponse,
  PageResponse,
  VesselDetailResponse,
  VesselCompanyLinkResponse,
  VesselCompanyRole,
  VesselFilter,
  VesselRequest,
  VesselResponse,
} from './types';

/**
 * A vessel's editable fields echoed back out of a response. PUT /vessels/{id} replaces
 * the whole record, so changing one field (e.g. the owner) still has to resend the rest.
 */
export const toVesselRequest = (v: VesselResponse): VesselRequest => ({
  name: v.name,
  imoNumber: v.imoNumber,
  deadweightTonnage: v.deadweightTonnage,
  deadweightCargoCapacity: v.deadweightCargoCapacity,
  grainCapacityM3: v.grainCapacityM3,
  baleCapacityM3: v.baleCapacityM3,
  maximumDraft: v.maximumDraft,
  yearBuilt: v.yearBuilt,
  vesselType: v.vesselType,
  flag: v.flag,
  ownerId: v.ownerId,
  notes: v.notes,
});

export const vesselsApi = {
  search: (filter: VesselFilter) =>
    client
      .get<PageResponse<VesselResponse>>('/vessels', { params: cleanParams(filter) })
      .then((r) => r.data),

  get: (id: number) =>
    client.get<VesselDetailResponse>(`/vessels/${id}`).then((r) => r.data),

  // Owner-company addresses to circulate to live in api/circulations.ts alongside the
  // company and people equivalents — they share the circ/main selection rule, so keeping
  // the three together is what stops them drifting apart.

  create: (body: VesselRequest) =>
    client.post<VesselResponse>('/vessels', body).then((r) => r.data),

  update: (id: number, body: VesselRequest) =>
    client.put<VesselResponse>(`/vessels/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/vessels/${id}`).then((r) => r.data),

  links: (id: number) =>
    client.get<VesselCompanyLinkResponse[]>(`/vessels/${id}/links`).then((r) => r.data),

  /** Attach a company in one capacity, replacing whatever role it held on this vessel. */
  setLink: (id: number, companyId: number, role: VesselCompanyRole) =>
    client
      .put<VesselCompanyLinkResponse[]>(`/vessels/${id}/links/${companyId}`, {}, { params: { role } })
      .then((r) => r.data),

  removeLink: (id: number, companyId: number) =>
    client
      .delete<VesselCompanyLinkResponse[]>(`/vessels/${id}/links/${companyId}`)
      .then((r) => r.data),

  confirm: (id: number, confirmed: boolean, body?: ConfirmRequest) =>
    client
      .patch<VesselResponse>(`/vessels/${id}/confirm`, body ?? {}, { params: { confirmed } })
      .then((r) => r.data),

  setBanned: (id: number, banned: boolean) =>
    client
      .patch<VesselResponse>(`/vessels/${id}/ban`, {}, { params: { banned } })
      .then((r) => r.data),
};
