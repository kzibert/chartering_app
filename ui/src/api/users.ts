import { client, cleanParams } from './client';
import type { UserRole } from './auth';

export interface UserResponse {
  id: number;
  username: string;
  displayName?: string;
  role: UserRole;
  enabled: boolean;
  mustChangePassword: boolean;
  locked: boolean;
  lastLoginAt?: string;
  createdAt: string;
  createdBy?: string;
  tenantId: number;
  tenantName: string;
}

/** An account just created or reset; the password is present only when the server chose it. */
export interface UserPasswordResponse {
  user: UserResponse;
  temporaryPassword?: string;
}

export interface UserCreateRequest {
  username: string;
  displayName?: string;
  role: UserRole;
  tenantId?: number;
  password?: string;
}

export interface UserUpdateRequest {
  displayName?: string;
  role: UserRole;
}

export const usersApi = {
  list: (tenantId?: number) =>
    client
      .get<UserResponse[]>('/admin/users', { params: cleanParams({ tenantId }) })
      .then((r) => r.data),
  create: (body: UserCreateRequest) =>
    client.post<UserPasswordResponse>('/admin/users', body).then((r) => r.data),
  update: (id: number, body: UserUpdateRequest) =>
    client.put<UserResponse>(`/admin/users/${id}`, body).then((r) => r.data),
  disable: (id: number) =>
    client.post<UserResponse>(`/admin/users/${id}/disable`).then((r) => r.data),
  enable: (id: number) =>
    client.post<UserResponse>(`/admin/users/${id}/enable`).then((r) => r.data),
  resetPassword: (id: number) =>
    client.post<UserPasswordResponse>(`/admin/users/${id}/reset-password`).then((r) => r.data),
};
