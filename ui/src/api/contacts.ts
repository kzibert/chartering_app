import { client, cleanParams } from './client';
import type {
  ConfirmRequest,
  ContactFilter,
  ContactRequest,
  ContactResponse,
  PageResponse,
} from './types';

export const contactsApi = {
  search: (filter: ContactFilter) =>
    client
      .get<PageResponse<ContactResponse>>('/contacts', { params: cleanParams(filter) })
      .then((r) => r.data),

  get: (id: number) => client.get<ContactResponse>(`/contacts/${id}`).then((r) => r.data),

  create: (body: ContactRequest) =>
    client.post<ContactResponse>('/contacts', body).then((r) => r.data),

  update: (id: number, body: ContactRequest) =>
    client.put<ContactResponse>(`/contacts/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/contacts/${id}`).then((r) => r.data),

  confirm: (id: number, confirmed: boolean, body?: ConfirmRequest) =>
    client
      .patch<ContactResponse>(`/contacts/${id}/confirm`, body ?? {}, { params: { confirmed } })
      .then((r) => r.data),

  setBanned: (id: number, banned: boolean) =>
    client
      .patch<ContactResponse>(`/contacts/${id}/ban`, {}, { params: { banned } })
      .then((r) => r.data),
};
