import { client } from './client';

export type AliasKind = 'PORT' | 'AREA';

export interface DeskAlias {
  kind: AliasKind;
  id: number;
  alias: string;
  targetId: number;
  targetName: string;
}

export interface DeskAliasRequest {
  kind: AliasKind;
  targetId: number;
  alias: string;
}

export const vocabularyApi = {
  aliases: () => client.get<DeskAlias[]>('/vocabulary/aliases').then((r) => r.data),
  add: (body: DeskAliasRequest) => client.post<DeskAlias>('/vocabulary/aliases', body).then((r) => r.data),
  remove: (kind: AliasKind, id: number) => client.delete(`/vocabulary/aliases/${kind}/${id}`),
};
