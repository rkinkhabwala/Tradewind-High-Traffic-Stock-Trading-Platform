/**
 * The one way the app talks to the REST API (same origin; nginx or the Vite dev server proxies /api to the
 * gateway).
 *
 * - The access token lives only in memory (never localStorage). The refresh token is an httpOnly cookie scoped to
 *   /api/auth that JavaScript cannot read; POST /api/auth/refresh rotates it and returns a new access token.
 * - A 401 triggers one refresh, shared by all concurrent requests, and a single retry. If the refresh fails the
 *   session is over and listeners are told (the app goes to the login page).
 * - The access token is also refreshed shortly before it expires, so requests rarely hit a 401 at all.
 * - Mutations carry an Idempotency-Key; the retry after a refresh reuses it, so it can never act twice.
 * - Errors are RFC 7807 problem documents, surfaced as ApiError with the server's `code` when present.
 */

export class ApiError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly detail?: string;

  constructor(status: number, title: string, code?: string, detail?: string) {
    super(detail ?? title);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.detail = detail;
  }
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  body?: unknown;
  /** true generates a key; a string uses that key (callers retrying a user action pass the same one). */
  idempotencyKey?: string | true;
  signal?: AbortSignal;
}

const REFRESH_EARLY_MS = 60_000;

let accessToken: string | null = null;
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
let refreshing: Promise<boolean> | null = null;
const sessionEndedListeners = new Set<() => void>();

export function getAccessToken(): string | null {
  return accessToken;
}

export function setSession(token: string, expiresInSeconds: number): void {
  accessToken = token;
  clearTimeout(refreshTimer);
  const delay = Math.max(5_000, expiresInSeconds * 1000 - REFRESH_EARLY_MS);
  refreshTimer = setTimeout(() => void refreshSession(), delay);
}

export function clearSession(): void {
  accessToken = null;
  clearTimeout(refreshTimer);
  refreshTimer = undefined;
}

/** Called when the session can no longer be refreshed (expired, revoked, logged out elsewhere). */
export function onSessionEnded(listener: () => void): () => void {
  sessionEndedListeners.add(listener);
  return () => sessionEndedListeners.delete(listener);
}

interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

/** Exchanges the refresh cookie for a new access token. Concurrent callers share one request. */
export function refreshSession(): Promise<boolean> {
  refreshing ??= (async () => {
    try {
      const response = await fetch('/api/auth/refresh', { method: 'POST', credentials: 'same-origin' });
      if (!response.ok) {
        clearSession();
        return false;
      }
      const body = (await response.json()) as TokenResponse;
      setSession(body.accessToken, body.expiresIn);
      return true;
    } catch {
      return false;      // network error: keep the current token, the next request will try again
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

function send(path: string, options: RequestOptions, idempotencyKey: string | undefined): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (accessToken) {
    headers.Authorization = `Bearer ${accessToken}`;
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  if (idempotencyKey) {
    headers['Idempotency-Key'] = idempotencyKey;
  }
  return fetch(path, {
    method: options.method ?? 'GET',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: 'same-origin',
    signal: options.signal,
  });
}

async function toError(response: Response): Promise<ApiError> {
  let problem: { title?: string; detail?: string; code?: string } = {};
  try {
    problem = (await response.json()) as typeof problem;
  } catch {
    // not a problem document
  }
  return new ApiError(response.status, problem.title ?? response.statusText, problem.code, problem.detail);
}

export async function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const key = options.idempotencyKey === true ? crypto.randomUUID() : options.idempotencyKey;
  let response = await send(path, options, key);
  if (response.status === 401) {
    if (await refreshSession()) {
      response = await send(path, options, key);
    } else {
      sessionEndedListeners.forEach((listener) => listener());
    }
  }
  if (!response.ok) {
    throw await toError(response);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
