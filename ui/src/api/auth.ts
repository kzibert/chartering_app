import { client } from './client';

export type UserRole = 'USER' | 'TENANT_ADMIN' | 'PLATFORM_ADMIN';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  username: string;
  /** ISO instant — when this token stops being accepted. */
  expiresAt: string;
  /** An administrator chose this password; the server answers nothing else until it is replaced. */
  mustChangePassword: boolean;
}

export interface SessionResponse {
  userId: number;
  username: string;
  displayName?: string;
  role: UserRole;
  tenantId: number;
  tenantName: string;
  mustChangePassword: boolean;
}

export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

export const authApi = {
  login: (body: LoginRequest) =>
    client.post<LoginResponse>('/auth/login', body).then((r) => r.data),

  /**
   * Validates the stored token against the server. Called once on load: a token that has
   * expired, or that was signed with a key the server no longer has, is indistinguishable
   * from a good one in the browser, and finding out here is much tidier than every query on
   * the first screen failing at once.
   */
  me: () => client.get<SessionResponse>('/auth/me').then((r) => r.data),

  /** Returns a fresh token: changing the password revokes every token the account held. */
  changePassword: (body: ChangePasswordRequest) =>
    client.post<LoginResponse>('/auth/change-password', body).then((r) => r.data),
};

/** Roles nest; this is the server's `UserRole.atLeast`. */
const RANK: Record<UserRole, number> = { USER: 0, TENANT_ADMIN: 1, PLATFORM_ADMIN: 2 };
export const roleAtLeast = (role: UserRole | undefined, min: UserRole) =>
  role !== undefined && RANK[role] >= RANK[min];

export const ROLE_LABELS: Record<UserRole, string> = {
  USER: 'User',
  TENANT_ADMIN: 'Desk administrator',
  PLATFORM_ADMIN: 'Platform administrator',
};
