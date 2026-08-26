import { client, cleanParams } from './client';
import type {
  PageResponse,
  PositionFilter,
  PositionStatus,
  VesselPositionRequest,
  VesselPositionResponse,
} from './types';

/**
 * A position's editable fields echoed back out of a response. PUT replaces the whole
 * record, so changing one field still has to resend the rest.
 */
export const toPositionRequest = (p: VesselPositionResponse): VesselPositionRequest => ({
  vesselId: p.vessel.id,
  status: p.status,
  openPortId: p.openPortId,
  openPortText: p.openPortText,
  openAreaId: p.openAreaId,
  openFrom: p.openFrom,
  openTo: p.openTo,
  openText: p.openText,
  lastCargo: p.lastCargo,
  cargoPreferences: p.cargoPreferences,
  reportedByCompanyId: p.reportedByCompanyId,
  reportedByPersonId: p.reportedByPersonId,
  sourceMailMessageId: p.sourceMailMessageId,
  reportedAt: p.reportedAt,
  notes: p.notes,
});

export const positionsApi = {
  search: (filter: PositionFilter) =>
    client
      .get<PageResponse<VesselPositionResponse>>('/positions', { params: cleanParams(filter) })
      .then((r) => r.data),

  get: (id: number) =>
    client.get<VesselPositionResponse>(`/positions/${id}`).then((r) => r.data),

  /**
   * Every position ever reported about one vessel, newest first. Nothing here is deleted
   * when it is replaced, so this is where two brokers disagreeing is visible — and where a
   * pattern shows up, like a ship that keeps opening in the Adriatic.
   */
  history: (vesselId: number) =>
    client.get<VesselPositionResponse[]>(`/positions/vessel/${vesselId}`).then((r) => r.data),

  create: (body: VesselPositionRequest) =>
    client.post<VesselPositionResponse>('/positions', body).then((r) => r.data),

  update: (id: number, body: VesselPositionRequest) =>
    client.put<VesselPositionResponse>(`/positions/${id}`, body).then((r) => r.data),

  setStatus: (id: number, status: PositionStatus) =>
    client
      .patch<VesselPositionResponse>(`/positions/${id}/status`, null, { params: { status } })
      .then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/positions/${id}`).then((r) => r.data),
};
