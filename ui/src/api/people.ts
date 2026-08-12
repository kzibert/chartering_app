import { client, cleanParams } from './client';
import type {
  PageResponse,
  PeopleFilter,
  PersonDetailResponse,
  PersonRequest,
  PersonResponse,
} from './types';

export const peopleApi = {
  /** Paginated search with each person's contacts attached — powers the People page. */
  search: (filter: PeopleFilter) =>
    client
      .get<PageResponse<PersonDetailResponse>>('/people/search', { params: cleanParams(filter) })
      .then((r) => r.data),

  /** `name` matches the full name or the greeting name; filters combine. */
  list: (companyId?: number, name?: string) =>
    client
      .get<PersonResponse[]>('/people', { params: cleanParams({ companyId, name }) })
      .then((r) => r.data),

  get: (id: number) => client.get<PersonResponse>(`/people/${id}`).then((r) => r.data),

  create: (body: PersonRequest) =>
    client.post<PersonResponse>('/people', body).then((r) => r.data),

  update: (id: number, body: PersonRequest) =>
    client.put<PersonResponse>(`/people/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/people/${id}`).then((r) => r.data),
};
