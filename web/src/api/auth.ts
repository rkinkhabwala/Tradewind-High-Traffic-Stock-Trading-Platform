import { ApiError, clearSession, setSession } from './client';

interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

export interface Registered {
  userId: string;
  accountId: string;
  email: string;
}

async function post(path: string, body?: unknown): Promise<Response> {
  return fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

async function fail(response: Response): Promise<never> {
  let problem: { title?: string; detail?: string; code?: string } = {};
  try {
    problem = await response.json();
  } catch {
    // empty body
  }
  throw new ApiError(response.status, problem.title ?? response.statusText, problem.code, problem.detail);
}

/** Sets the in-memory access token; the server sets the httpOnly refresh cookie. */
export async function login(email: string, password: string): Promise<void> {
  const response = await post('/api/auth/login', { email, password });
  if (!response.ok) {
    await fail(response);
  }
  const body = (await response.json()) as TokenResponse;
  setSession(body.accessToken, body.expiresIn);
}

export async function register(email: string, password: string): Promise<Registered> {
  const response = await post('/api/auth/register', { email, password });
  if (!response.ok) {
    await fail(response);
  }
  return (await response.json()) as Registered;
}

/** Revokes the refresh token server-side and clears the cookie; local state is cleared even if that fails. */
export async function logout(): Promise<void> {
  try {
    await post('/api/auth/logout');
  } finally {
    clearSession();
  }
}
