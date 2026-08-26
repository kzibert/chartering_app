import { client, cleanParams } from './client';
import type {
  CargoFilter,
  CargoRequest,
  CargoResponse,
  CargoStatus,
  PageResponse,
} from './types';

/**
 * A cargo's editable fields echoed back out of a response. PUT /cargoes/{id} replaces the
 * whole record, so changing one field still has to resend the rest.
 *
 * quantityMin/Max are carried across deliberately. They may have been derived from a
 * percentage tolerance or typed by hand, and the API cannot tell which — so a form that
 * dropped them would silently re-derive, quietly discarding a range a broker had corrected.
 */
export const toCargoRequest = (c: CargoResponse): CargoRequest => ({
  commodity: c.commodity,
  status: c.status,
  statusNote: c.statusNote,
  stowageFactor: c.stowageFactor,
  quantity: c.quantity,
  quantityUnit: c.quantityUnit,
  quantityTolerance: c.quantityTolerance,
  quantityMin: c.quantityMin,
  quantityMax: c.quantityMax,
  loadPortId: c.loadPortId,
  loadPortText: c.loadPortText,
  loadAreaId: c.loadAreaId,
  dischargePortId: c.dischargePortId,
  dischargePortText: c.dischargePortText,
  dischargeAreaId: c.dischargeAreaId,
  laycanFrom: c.laycanFrom,
  laycanTo: c.laycanTo,
  laycanText: c.laycanText,
  maxDraft: c.maxDraft,
  minDwt: c.minDwt,
  maxDwt: c.maxDwt,
  maxAgeYears: c.maxAgeYears,
  requiresGeared: c.requiresGeared,
  requiresGrainFitted: c.requiresGrainFitted,
  requiresImoFitted: c.requiresImoFitted,
  freightIdea: c.freightIdea,
  commission: c.commission,
  terms: c.terms,
  loadRate: c.loadRate,
  dischargeRate: c.dischargeRate,
  chartererCompanyId: c.chartererCompanyId,
  brokerCompanyId: c.brokerCompanyId,
  brokerPersonId: c.brokerPersonId,
  sourceMailMessageId: c.sourceMailMessageId,
  receivedAt: c.receivedAt,
  notes: c.notes,
});

export const cargoesApi = {
  search: (filter: CargoFilter) =>
    client
      .get<PageResponse<CargoResponse>>('/cargoes', { params: cleanParams(filter) })
      .then((r) => r.data),

  get: (id: number) => client.get<CargoResponse>(`/cargoes/${id}`).then((r) => r.data),

  create: (body: CargoRequest) =>
    client.post<CargoResponse>('/cargoes', body).then((r) => r.data),

  update: (id: number, body: CargoRequest) =>
    client.put<CargoResponse>(`/cargoes/${id}`, body).then((r) => r.data),

  /**
   * Move a cargo along without resending the rest of it — its own endpoint for the reason
   * confirm and ban have theirs: marking a cargo fixed from a list is one click on one
   * fact, and routing it through the form would let a stale form revert five others.
   */
  setStatus: (id: number, status: CargoStatus, note?: string) =>
    client
      .patch<CargoResponse>(`/cargoes/${id}/status`, null, { params: cleanParams({ status, note }) })
      .then((r) => r.data),

  remove: (id: number) => client.delete<void>(`/cargoes/${id}`).then((r) => r.data),
};
