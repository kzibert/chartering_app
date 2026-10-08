import { client } from './client';
import type { UserPasswordResponse } from './users';

export type TenantStatus = 'ACTIVE' | 'SUSPENDED';

export interface TenantResponse {
  id: number;
  name: string;
  status: TenantStatus;
  createdAt: string;
  users: number;
}

export interface TenantCreateRequest {
  name: string;
  adminUsername: string;
  adminDisplayName?: string;
  adminPassword?: string;
}

export interface TenantCreatedResponse {
  tenant: TenantResponse;
  admin: UserPasswordResponse;
}

export const tenantsApi = {
  list: () => client.get<TenantResponse[]>('/admin/tenants').then((r) => r.data),
  create: (body: TenantCreateRequest) =>
    client.post<TenantCreatedResponse>('/admin/tenants', body).then((r) => r.data),
  rename: (id: number, name: string) =>
    client.put<TenantResponse>(`/admin/tenants/${id}`, { name }).then((r) => r.data),
  suspend: (id: number) =>
    client.post<TenantResponse>(`/admin/tenants/${id}/suspend`).then((r) => r.data),
  activate: (id: number) =>
    client.post<TenantResponse>(`/admin/tenants/${id}/activate`).then((r) => r.data),
};
