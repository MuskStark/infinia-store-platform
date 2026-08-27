/**
 * Typed API client. Types are generated from the OpenAPI 3.1 contract
 * (store-contract/src/main/resources/contract/openapi.yaml) via
 * `yarn gen:api` — the contract is the single source of truth (design §10.1).
 */
import type { components, paths } from './schema';

export type ApiError = {
  code?: string;
  title?: string;
  detail?: string;
  status?: number;
  traceId?: string;
};

export class ApiRequestError extends Error implements ApiError {
  code?: string;
  title?: string;
  detail?: string;
  status: number;
  traceId?: string;

  constructor(body: ApiError) {
    super(body.detail ?? body.title ?? 'Request failed');
    this.code = body.code;
    this.title = body.title;
    this.detail = body.detail;
    this.status = body.status ?? 0;
    this.traceId = body.traceId;
  }
}

const TOKEN_STORAGE = 'infinia.store.token';

/** Access token lives in memory by default; sessionStorage bridges reloads during dev. */
let accessToken: string | null = null;

export function setAccessToken(token: string | null) {
  accessToken = token;
  if (token) {
    sessionStorage.setItem(TOKEN_STORAGE, token);
  } else {
    sessionStorage.removeItem(TOKEN_STORAGE);
  }
}

export function getAccessToken(): string | null {
  if (accessToken === null && typeof sessionStorage !== 'undefined') {
    accessToken = sessionStorage.getItem(TOKEN_STORAGE);
  }
  return accessToken;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) {
    headers.set('Content-Type', 'application/json');
  }
  const token = getAccessToken();
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const response = await fetch(path, { ...init, headers });
  if (response.status === 204) {
    return undefined as T;
  }
  const isJson = response.headers.get('content-type')?.includes('json');
  const body = isJson ? await response.json() : await response.text();
  if (!response.ok) {
    throw new ApiRequestError(
      (typeof body === 'object' ? body : { detail: String(body) }) as ApiError,
    );
  }
  return body as T;
}

export const api = {
  get: <T>(path: keyof paths | string) => request<T>(path as string),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  /** Raw PUT for presigned upload URLs (no auth header — the ticket authorizes). */
  putRaw: async (url: string, data: ArrayBuffer | Blob) => {
    const response = await fetch(url, { method: 'PUT', body: data });
    if (!response.ok) {
      throw new ApiRequestError({ status: response.status, detail: 'Upload failed' });
    }
  },
};

// ---- typed DTO aliases generated from the contract ----
export type CatalogItem = components['schemas']['CatalogItem'];
export type CatalogPage = components['schemas']['CatalogPage'];
export type ListingDetail = components['schemas']['ListingDetail'];
export type ListingRelease = components['schemas']['ListingRelease'];
export type ResolveResponse = components['schemas']['ResolveResponse'];
export type DownloadTicket = components['schemas']['DownloadTicket'];
export type PublicUser = components['schemas']['PublicUser'];
export type Library = components['schemas']['Library'];
export type Review = components['schemas']['Review'];
export type PublisherRelease = components['schemas']['PublisherRelease'];
export type UploadSession = components['schemas']['UploadSession'];
export type SubmitResult = components['schemas']['SubmitResult'];
export type RatingsPage = components['schemas']['RatingsPage'];
export type Rating = components['schemas']['Rating'];
export type Report = components['schemas']['Report'];
export type AuditEvent = components['schemas']['AuditEvent'];
export type Organization = components['schemas']['Organization'];
export type OrganizationMember = components['schemas']['OrganizationMember'];
export type Webhook = components['schemas']['Webhook'];
export type InstalledItem = components['schemas']['InstalledItem'];
