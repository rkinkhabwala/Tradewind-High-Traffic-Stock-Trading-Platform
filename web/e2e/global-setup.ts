import { request } from '@playwright/test';
import { healthyStreamInstances, saveUser } from './support';

const NY = 'America/New_York';

/**
 * Preconditions for the acceptance run, checked up front so a failure says what to fix:
 * the stack is up with 3 healthy stream-service instances, and the simulated market is open with time to spare.
 * Then a fresh user is registered and funded through the public API (the demo accounts are left alone).
 */
export default async function globalSetup(): Promise<void> {
  const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:3001';
  const healthy = healthyStreamInstances();
  if (healthy < 3) {
    throw new Error(`Need 3 healthy stream-service instances, found ${healthy}. Run: `
      + 'scripts/compose.sh up -d --build --scale stream-service=3');
  }

  const api = await request.newContext({ baseURL });
  const email = `e2e-${Date.now()}@tradewind.test`;
  const password = 'e2e-password-123';
  const registered = await api.post('/api/auth/register', { data: { email, password } });
  if (!registered.ok()) {
    throw new Error(`register failed: ${registered.status()} ${await registered.text()}`);
  }
  const login = await api.post('/api/auth/login', { data: { email, password } });
  const { accessToken } = (await login.json()) as { accessToken: string };
  const auth = { Authorization: `Bearer ${accessToken}` };

  const deposit = await api.post('/api/account/deposits', {
    headers: { ...auth, 'Idempotency-Key': crypto.randomUUID() },
    data: { amountCents: 10_000_000 },
  });
  if (!deposit.ok()) {
    throw new Error(`deposit failed: ${deposit.status()} ${await deposit.text()}`);
  }

  // Market time comes from the quotes (simulated clock); market orders need the market open.
  const quotes = (await (await api.get('/api/quotes?symbols=AAPL', { headers: auth })).json()) as { ts: string }[];
  const marketTime = new Date(quotes[0].ts);
  const parts = Object.fromEntries(new Intl.DateTimeFormat('en-US', { timeZone: NY, weekday: 'short', hour: 'numeric',
    minute: 'numeric', hourCycle: 'h23' }).formatToParts(marketTime).map((p) => [p.type, p.value]));
  const minutes = Number(parts.hour) * 60 + Number(parts.minute);
  const open = !['Sat', 'Sun'].includes(parts.weekday) && minutes >= 9 * 60 + 30 && minutes < 15 * 60 + 50;
  if (!open) {
    throw new Error(`The simulated market is closed or about to close (market time ${marketTime.toISOString()}). `
      + 'Run scripts/next-market-open.sh to move the shared clock to the next trading day\'s open.');
  }
  await api.dispose();
  saveUser({ email, password });
  console.log(`[e2e] user ${email} funded with $100,000; market time ${marketTime.toISOString()}; `
    + `${healthy} stream-service instances`);
}
