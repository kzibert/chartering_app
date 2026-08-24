import { client } from './client';

/**
 * The two halves of a contacts import. Preview writes nothing; import writes what the user
 * left of the preview.
 *
 * The whole parse travels to the browser and back rather than being held server-side
 * between the calls — there is no staging table and no import id, so an abandoned review
 * costs nothing and leaves nothing to clean up.
 */

/** One address or number off a row of the file. */
export interface ImportContact {
  kind: 'email' | 'phone';
  value: string;
  /** Work / Mobile / Direct / Fax, read off the file. Phones only. */
  label?: string;
  /** This value is already on file against the same company. Unticked by default. */
  duplicate: boolean;
  warning?: string;
}

export interface ImportCompany {
  /** Stable handle. People point at their company by this, so a rename does not orphan them. */
  key: string;
  /** The name exactly as the file spelled it, kept so a correction can be shown against it. */
  sourceName: string;
  name: string;
  /** An existing company to file everything under, or absent to create one. */
  matchedId?: number;
  matchedName?: string;
  matchType: 'exact' | 'similar' | 'new';
  cityName?: string;
  country?: string;
  website?: string;
  notes?: string;
  /** Addresses on the organisation itself, plus any shared by several of its people. */
  contacts: ImportContact[];
  warnings: string[];
}

export interface ImportPerson {
  key: string;
  companyKey: string;
  sourceName: string;
  fullName: string;
  title?: string;
  jobTitle?: string;
  greetingName?: string;
  matchedId?: number;
  matchType: 'exact' | 'new';
  notes?: string;
  contacts: ImportContact[];
  warnings: string[];
}

export interface ImportCounts {
  companiesNew: number;
  companiesMatched: number;
  peopleNew: number;
  peopleMatched: number;
  emails: number;
  phones: number;
  duplicates: number;
  warnings: number;
}

export interface ContactImportPreview {
  fileName?: string;
  companies: ImportCompany[];
  people: ImportPerson[];
  /** Trouble with the file as a whole rather than with any one row. */
  fileWarnings: string[];
  counts: ImportCounts;
}

/** What gets posted back. Rows the user dropped are absent rather than flagged. */
export interface ContactImportRequest {
  companies: {
    key: string;
    name: string;
    matchedId?: number;
    cityName?: string;
    country?: string;
    website?: string;
    notes?: string;
    contacts: { kind: string; value: string; label?: string }[];
  }[];
  people: {
    key: string;
    companyKey: string;
    fullName: string;
    title?: string;
    jobTitle?: string;
    greetingName?: string;
    matchedId?: number;
    notes?: string;
    contacts: { kind: string; value: string; label?: string }[];
  }[];
}

export interface ContactImportResult {
  companiesCreated: number;
  companiesMatched: number;
  peopleCreated: number;
  peopleMatched: number;
  contactsCreated: number;
  contactsSkipped: number;
  messages: string[];
}

export const contactImportApi = {
  preview: (file: File) => {
    const body = new FormData();
    body.append('file', file);
    // No explicit Content-Type: the browser has to set it, because only it knows the
    // multipart boundary. Naming the type here and omitting the boundary makes the request
    // unparseable at the other end.
    return client
      .post<ContactImportPreview>('/contacts/import/preview', body)
      .then((r) => r.data);
  },

  commit: (body: ContactImportRequest) =>
    client.post<ContactImportResult>('/contacts/import', body).then((r) => r.data),
};
