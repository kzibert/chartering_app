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

  setMain: (id: number, main: boolean) =>
    client
      .patch<ContactResponse>(`/contacts/${id}/main`, {}, { params: { main } })
      .then((r) => r.data),

  /** Flag an email for use in circulations. Additive — nothing else is demoted. */
  setCirc: (id: number, circ: boolean) =>
    client
      .patch<ContactResponse>(`/contacts/${id}/circ`, null, { params: { circ } })
      .then((r) => r.data),

  /**
   * Flag an email as never to be circulated to. Clears `circ`, which is its opposite.
   * Excluded from bulk collection and dropped again at send time.
   */
  setNoCirc: (id: number, noCirc: boolean) =>
    client
      .patch<ContactResponse>(`/contacts/${id}/no-circ`, null, { params: { noCirc } })
      .then((r) => r.data),

  /**
   * Record that a phone number is on WhatsApp, as seen by the user in the wa.me check.
   * Never inferred — nothing here can ask WhatsApp anything.
   */
  setHasWhatsapp: (id: number, hasWhatsapp: boolean) =>
    client
      .patch<ContactResponse>(`/contacts/${id}/whatsapp`, null, { params: { hasWhatsapp } })
      .then((r) => r.data),

  setWorking: (id: number, working: boolean) =>
    client
      .patch<ContactResponse>(`/contacts/${id}/working`, {}, { params: { working } })
      .then((r) => r.data),
};
