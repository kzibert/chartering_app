// TypeScript mirrors of the clean-api DTOs (field names must match the JSON exactly).

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
}

export interface VesselResponse {
  id: number;
  name: string;
  imoNumber?: string;
  deadweightTonnage?: number;
  deadweightCargoCapacity?: number;
  grainCapacityM3?: number;
  baleCapacityM3?: number;
  maximumDraft?: number;
  yearBuilt?: number;
  vesselType?: string;
  flag?: string;
  ownerId?: number;
  ownerName?: string;
  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  confirmNotes?: string;
}

export interface VesselDetailResponse {
  vessel: VesselResponse;
  owner?: CompanyResponse;
  ownerContacts: ContactResponse[];
}

export interface VesselRequest {
  name: string;
  imoNumber?: string;
  deadweightTonnage?: number;
  deadweightCargoCapacity?: number;
  grainCapacityM3?: number;
  baleCapacityM3?: number;
  maximumDraft?: number;
  yearBuilt?: number;
  vesselType?: string;
  flag?: string;
  ownerId?: number;
  notes?: string;
}

export interface CompanyResponse {
  id: number;
  name: string;
  shipowner: boolean;
  charterer: boolean;
  broker: boolean;
  agent: boolean;
  cityName?: string;
  notes?: string;
  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  confirmNotes?: string;
}

export interface CompanyDetailResponse {
  company: CompanyResponse;
  contacts: ContactResponse[];
  vessels: VesselResponse[];
}

export interface CompanyRequest {
  name: string;
  shipowner: boolean;
  charterer: boolean;
  broker: boolean;
  agent: boolean;
  cityName?: string;
  notes?: string;
}

export interface PersonResponse {
  id: number;
  fullName: string;
  title?: string;
  greetingName?: string;
  companyId?: number;
  companyName?: string;
  notes?: string;
}

export interface PersonRequest {
  fullName: string;
  title?: string;
  greetingName?: string;
  companyId?: number;
  notes?: string;
}

export interface ContactResponse {
  id: number;
  personId?: number;
  personName?: string;
  title?: string;
  greetingName?: string;
  companyId?: number;
  contactKind: string; // 'email' | 'phone'
  contactValue: string;
  notes?: string;
  confirmed: boolean;
  confirmedAt?: string;
  confirmedBy?: string;
  confirmNotes?: string;
}

export interface ContactRequest {
  personId?: number;
  companyId?: number;
  contactKind: string;
  contactValue: string;
  notes?: string;
}

export interface ConfirmRequest {
  confirmedBy?: string;
  confirmNotes?: string;
}

export interface LookupResponse {
  id: number;
  name: string;
}

// Query param shapes for list endpoints.
export interface PageParams {
  page?: number;
  size?: number;
  sort?: string; // e.g. "name,asc"
}

export interface VesselFilter extends PageParams {
  name?: string;
  imoNumber?: string;
  minDwt?: number;
  maxDwt?: number;
  minDwcc?: number;
  maxDwcc?: number;
  minGrain?: number;
  maxGrain?: number;
  minBale?: number;
  maxBale?: number;
  minDraft?: number;
  maxDraft?: number;
  minYear?: number;
  maxYear?: number;
  vesselType?: string[];
  flag?: string[];
  ownerId?: number;
  ownerName?: string;
  confirmed?: boolean;
}

export interface CompanyFilter extends PageParams {
  name?: string;
  city?: string;
  shipowner?: boolean;
  charterer?: boolean;
  broker?: boolean;
  agent?: boolean;
  confirmed?: boolean;
  regionId?: number;
  portId?: number;
  tonnageCategoryId?: number;
}

export interface ContactFilter extends PageParams {
  kind?: string;
  value?: string;
  companyId?: number;
  confirmed?: boolean;
}
