import axios from 'axios';
import { notification } from 'antd';
import { clearToken, getToken } from '../auth/store';

/**
 * Relative by default — Vite proxies /api in dev, nginx proxies it in the container, and a
 * static host with a rewrite rule proxies it too. In all three the browser sees one origin
 * and CORS never enters into it.
 *
 * VITE_API_BASE_URL is for the fourth case: the bundle served from somewhere with nothing
 * in front of the API to proxy for it — a static site whose host cannot rewrite. Then the
 * calls have to name the API outright, which makes them cross-origin, which is what
 * CORS_ORIGINS on the API exists to allow. Baked in at build time, so switching it means a
 * rebuild; that is fine, because the address only changes when the deployment does.
 */
const API_BASE = import.meta.env.VITE_API_BASE_URL?.replace(/\/+$/, '') ?? '';

export const client = axios.create({
  baseURL: `${API_BASE}/api/v1`,
});

// Every request carries the bearer token, if there is one. Read per request rather than set
// once on the instance, so a login or a logout takes effect on the very next call without
// anything having to rebuild the client.
client.interceptors.request.use((config) => {
  const token = getToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

/** Login failures are the login form's business, not the notification tray's. */
const isLoginCall = (url?: string) => (url ?? '').includes('/auth/login');

// Surface the backend's GlobalExceptionHandler body ({status, error, message}) to the user.
client.interceptors.response.use(
  (res) => res,
  (error) => {
    const status = error?.response?.data?.status ?? error?.response?.status;
    const url = error?.config?.url as string | undefined;

    // 401 means the token is gone, expired, or was never good. Dropping it swaps the whole
    // app for the login screen (App.tsx watches the same value), so there is no redirect to
    // perform and no route to guard — and one notification says so, rather than one per
    // query that happened to be in flight when the session ended.
    if (status === 401 && !isLoginCall(url)) {
      if (getToken()) {
        clearToken();
        notification.warning({
          message: 'Session expired',
          description: 'Please log in again.',
        });
      }
      return Promise.reject(error);
    }

    if (isLoginCall(url)) return Promise.reject(error);

    const data = error?.response?.data;
    const message = data?.message ?? error?.message ?? 'Request failed';
    notification.error({
      message: status ? `Error ${status}` : 'Error',
      description: String(message),
    });
    return Promise.reject(error);
  },
);

// Drop undefined/null/empty-string/empty-array params so filters stay clean.
export function cleanParams(params: object): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v) && v.length === 0) continue;
    out[k] = v;
  }
  return out;
}
