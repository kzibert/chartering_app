import { client, cleanParams } from './client';
import type {
  CompanyDetailResponse,
  CompanyFilter,
  CompanyRequest,
  CompanyResponse,
  ConfirmRequest,
  ContactResponse,
  PageResponse,
  VesselResponse,
} from './types';

export const companiesApi = {
  search: (filter: CompanyFilter) =>
    client
      .get<PageResponse<CompanyResponse>>('/companies', { params: cleanParams(filter) })
      .then((r) => r.data),

  get: (id: number) =>
    client.get<CompanyDetailResponse>(`/companies/${id}`).then((r) => r.data),

  contacts: (id: number) =>
    client.get<ContactResponse[]>(`/companies/${id}/contacts`).then((r) => r.data),

  vessels: (id: number) =>
    client.get<VesselResponse[]>(`/companies/${id}/vessels`).then((r) => r.data),

  create: (body: CompanyRequest) =>
    client.post<CompanyResponse>('/companies', body).then((r) => r.data),

  update: (id: number, body: CompanyRequest) =>
    client.put<CompanyResponse>(`/companies/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/companies/${id}`).then((r) => r.data),

  confirm: (id: number, confirmed: boolean, body?: ConfirmRequest) =>
    client
      .patch<CompanyResponse>(`/companies/${id}/confirm`, body ?? {}, { params: { confirmed } })
      .then((r) => r.data),
};
