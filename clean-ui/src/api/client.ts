import axios from 'axios';
import { notification } from 'antd';

// Relative base: Vite proxies /api in dev, nginx proxies /api in the container.
export const client = axios.create({
  baseURL: '/api/v1',
});

// Surface the backend's GlobalExceptionHandler body ({status, error, message}) to the user.
client.interceptors.response.use(
  (res) => res,
  (error) => {
    const data = error?.response?.data;
    const message = data?.message ?? error?.message ?? 'Request failed';
    const status = data?.status ?? error?.response?.status;
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
