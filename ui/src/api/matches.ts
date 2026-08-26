import { client, cleanParams } from './client';
import type {
  MatchOutcomeRequest,
  MatchResponse,
  MatchSummaryResponse,
} from './types';

/**
 * Nothing here is stored on the server, and that is deliberate: a score goes stale the
 * moment a position or a cargo moves, so it is computed on the request. The one thing that
 * IS stored is what a person decided about a pairing — without it, the screen proposes the
 * same fifteen ships every morning, four of them already offered.
 */
export const matchesApi = {
  /** Every live cargo with the tonnage against it counted. The tab's landing view. */
  overview: () => client.get<MatchSummaryResponse[]>('/matches').then((r) => r.data),

  forCargo: (cargoId: number, includeRuledOut = false, minScore?: number) =>
    client
      .get<MatchResponse[]>(`/matches/cargo/${cargoId}`, {
        params: cleanParams({ includeRuledOut, minScore }),
      })
      .then((r) => r.data),

  /** The same scorer the other way round — cargoes for one ship's position. */
  forPosition: (positionId: number, includeRuledOut = false, minScore?: number) =>
    client
      .get<MatchResponse[]>(`/matches/position/${positionId}`, {
        params: cleanParams({ includeRuledOut, minScore }),
      })
      .then((r) => r.data),

  decide: (cargoId: number, vesselId: number, body: MatchOutcomeRequest) =>
    client
      .put<MatchResponse>(`/matches/cargo/${cargoId}/vessel/${vesselId}`, body)
      .then((r) => r.data),

  clear: (cargoId: number, vesselId: number) =>
    client.delete<void>(`/matches/cargo/${cargoId}/vessel/${vesselId}`).then((r) => r.data),
};
