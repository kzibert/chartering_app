import { client } from './client';
import type {
  EmailFooterRequest,
  EmailFooterResponse,
  EmailTemplateRequest,
  EmailTemplateResponse,
} from './types';

export const emailTemplatesApi = {
  list: () => client.get<EmailTemplateResponse[]>('/email-templates').then((r) => r.data),

  create: (body: EmailTemplateRequest) =>
    client.post<EmailTemplateResponse>('/email-templates', body).then((r) => r.data),

  update: (id: number, body: EmailTemplateRequest) =>
    client.put<EmailTemplateResponse>(`/email-templates/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/email-templates/${id}`).then((r) => r.data),
};

export const emailFootersApi = {
  list: () => client.get<EmailFooterResponse[]>('/email-footers').then((r) => r.data),

  create: (body: EmailFooterRequest) =>
    client.post<EmailFooterResponse>('/email-footers', body).then((r) => r.data),

  update: (id: number, body: EmailFooterRequest) =>
    client.put<EmailFooterResponse>(`/email-footers/${id}`, body).then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/email-footers/${id}`).then((r) => r.data),
};
