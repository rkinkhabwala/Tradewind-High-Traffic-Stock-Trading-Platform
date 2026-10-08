import { execFileSync } from 'node:child_process';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, type Page, type WebSocket } from '@playwright/test';

export const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');
const RESULTS_DIR = resolve(REPO_ROOT, 'web', 'test-results.nosync');
const USER_FILE = resolve(RESULTS_DIR, 'e2e-user.json');
const REPORT_FILE = resolve(RESULTS_DIR, 'phase2-acceptance.json');

export interface E2eUser {
  email: string;
  password: string;
}

export function saveUser(user: E2eUser): void {
  mkdirSync(RESULTS_DIR, { recursive: true });
  writeFileSync(USER_FILE, JSON.stringify(user));
}

export function loadUser(): E2eUser {
  return JSON.parse(readFileSync(USER_FILE, 'utf8')) as E2eUser;
}

/** Appends one acceptance result to test-results.nosync/phase2-acceptance.json and prints it. */
export function report(name: string, result: Record<string, unknown>): void {
  mkdirSync(RESULTS_DIR, { recursive: true });
  let all: Record<string, unknown> = {};
  try {
    all = JSON.parse(readFileSync(REPORT_FILE, 'utf8'));
  } catch {
    // first result
  }
  all[name] = { ...result, measuredAt: new Date().toISOString() };
  writeFileSync(REPORT_FILE, `${JSON.stringify(all, null, 2)}\n`);
  console.log(`\n[acceptance] ${name}: ${JSON.stringify(result)}`);
}

/** Runs docker compose from the repository (non-build commands work despite the folder name). */
export function compose(...args: string[]): string {
  return execFileSync('docker', ['compose', ...args], { cwd: REPO_ROOT, encoding: 'utf8' });
}

export function docker(...args: string[]): string {
  return execFileSync('docker', args, { cwd: REPO_ROOT, encoding: 'utf8' });
}

export function healthyStreamInstances(): number {
  return compose('ps', 'stream-service', '--format', '{{.Health}}').split('\n').filter((h) => h.trim() === 'healthy')
    .length;
}

/** Logs in through the UI (each page gets its own refresh-token chain; nothing is shared between tests). */
export async function login(page: Page, user: E2eUser): Promise<void> {
  await page.goto('/login');
  await page.getByLabel('Email').fill(user.email);
  await page.getByLabel('Password').fill(user.password);
  await page.getByRole('button', { name: 'Log in' }).click();
  await page.waitForURL((url) => url.pathname === '/');
  await expectLive(page);
}

export async function expectLive(page: Page): Promise<void> {
  await expect(page.locator('[data-state]')).toHaveAttribute('data-state', 'open', { timeout: 15_000 });
}

export async function instanceOf(page: Page): Promise<string> {
  const id = await page.locator('[data-instance]').getAttribute('data-instance');
  if (!id) {
    throw new Error('no stream-service instance shown');
  }
  return id;
}

export interface QuoteFrame {
  symbol: string;
  last: string;
  ts: string;
}

/** Records every quote frame the page receives on its WebSocket(s), across reconnects. */
export function recordQuotes(page: Page): QuoteFrame[] {
  const quotes: QuoteFrame[] = [];
  page.on('websocket', (ws: WebSocket) => {
    ws.on('framereceived', (frame) => {
      if (typeof frame.payload !== 'string' || !frame.payload.includes('"quote"')) {
        return;
      }
      const message = JSON.parse(frame.payload) as { type: string } & QuoteFrame;
      if (message.type === 'quote') {
        quotes.push({ symbol: message.symbol, last: message.last, ts: message.ts });
      }
    });
  });
  return quotes;
}

/** Nearest-rank percentile of already sorted values. */
export function percentile(sorted: number[], p: number): number {
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}
