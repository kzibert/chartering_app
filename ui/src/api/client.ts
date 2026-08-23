import axios from 'axios';
import { notification } from 'antd';
import { clearToken, getToken } from '../auth/store';

// Relative base: Vite proxies /api in dev, nginx proxies /api in the container.
export const client = axios.create({
  baseURL: '/api/v1',
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
