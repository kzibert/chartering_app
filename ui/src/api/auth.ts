import { client } from './client';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  username: string;
  /** ISO instant — when this token stops being accepted. */
  expiresAt: string;
}

export interface SessionResponse {
  username: string;
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
};
