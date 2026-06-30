import { client, cleanParams } from './client';
import type { PersonRequest, PersonResponse } from './types';

export const peopleApi = {
  list: (companyId?: number) =>
    client
      .get<PersonResponse[]>('/people', { params: cleanParams({ companyId }) })
      .then((r) => r.data),

  get: (id: number) => client.get<PersonResponse>(`/people/${id}`).then((r) => r.data),

  create: (body: PersonRequest) =>
    client.post<PersonResponse>('/people', body).then((r) => r.data),

  update: (id: number, body: PersonRequest) =>
    client.put<PersonResponse>(`/people/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/people/${id}`).then((r) => r.data),
};
