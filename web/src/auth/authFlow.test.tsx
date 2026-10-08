import { QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { createQueryClient, routes } from '../App';
import { fakeFetch, json } from '../test/fakeFetch';
import { AuthProvider } from './AuthProvider';

const portfolio = { cashCents: 9_800_000, cashReservedCents: 12_345, buyingPowerCents: 9_800_000,
  totalValueCents: 10_010_000, livePrices: true, accountId: 'a', positions: [], positionsValueCents: 0, dayChangeCents: 0 };
const token = { accessToken: 'access', tokenType: 'Bearer', expiresIn: 900 };

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  render(
    <QueryClientProvider client={createQueryClient()}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>,
  );
  return router;
}

describe('sign-in flow', () => {
  it('sends a visitor without a session to the login page', async () => {
    fakeFetch({ 'POST /api/auth/refresh': () => json(401) });
    const router = renderAt('/');
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
  });

  it('keeps a returning user signed in through the refresh cookie (no token in storage)', async () => {
    fakeFetch({
      'POST /api/auth/refresh': () => json(200, token),
      'GET /api/portfolio': (c) => (c.headers.authorization === 'Bearer access' ? json(200, portfolio) : json(401)),
    });
    renderAt('/');
    expect(await screen.findByText('$98,123.45')).toBeInTheDocument();
    expect(screen.getByText('$98,000.00')).toBeInTheDocument();
    expect(screen.getByText('$123.45')).toBeInTheDocument();
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('logs in and returns to the page that was asked for', async () => {
    const http = fakeFetch({
      'POST /api/auth/refresh': () => json(401),
      'POST /api/auth/login': (c) =>
        (c.body as { password: string }).password === 'right-password' ? json(200, token) : json(401),
      'GET /api/portfolio': () => json(200, portfolio),
    });
    const router = renderAt('/');
    await screen.findByRole('heading', { name: 'Log in' });

    await userEvent.type(screen.getByLabelText('Email'), 'demo1@tradewind.test');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Incorrect email or password.');

    await userEvent.clear(screen.getByLabelText('Password'));
    await userEvent.type(screen.getByLabelText('Password'), 'right-password');
    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByText('$98,123.45')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
    expect(http.calls.filter((c) => c.path === '/api/auth/login').at(-1)?.body).toEqual({
      email: 'demo1@tradewind.test', password: 'right-password' });
  });

  it('validates registration before calling the server and shows server conflicts', async () => {
    const http = fakeFetch({
      'POST /api/auth/refresh': () => json(401),
      'POST /api/auth/register': () => json(409, { title: 'Conflict', detail: 'Email already registered' }),
    });
    renderAt('/register');
    await screen.findByRole('heading', { name: 'Create your account' });

    await userEvent.type(screen.getByLabelText('Email'), 'new@tradewind.test');
    await userEvent.type(screen.getByLabelText(/^Password/), 'short');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Password must be 8–72 characters.');
    expect(http.count('POST /api/auth/register')).toBe(0);

    await userEvent.type(screen.getByLabelText(/^Password/), '-long-enough');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Email already registered');
  });

  it('registers, signs in and lands on Home', async () => {
    fakeFetch({
      'POST /api/auth/refresh': () => json(401),
      'POST /api/auth/register': () => json(201, { userId: 'u', accountId: 'a', email: 'new@tradewind.test' }),
      'POST /api/auth/login': () => json(200, token),
      'GET /api/portfolio': () => json(200, { ...portfolio, totalValueCents: 0, buyingPowerCents: 0, cashReservedCents: 0 }),
    });
    const router = renderAt('/register');
    await screen.findByRole('heading', { name: 'Create your account' });
    await userEvent.type(screen.getByLabelText('Email'), 'new@tradewind.test');
    await userEvent.type(screen.getByLabelText(/^Password/), 'long-enough-password');
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }));

    expect(await screen.findByLabelText('Portfolio')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
  });

  it('logs out: revokes the cookie server-side and returns to login', async () => {
    const http = fakeFetch({
      'POST /api/auth/refresh': () => json(200, token),
      'GET /api/portfolio': () => json(200, portfolio),
      'POST /api/auth/logout': () => new Response(null, { status: 204 }),
    });
    const router = renderAt('/');
    await screen.findByText('$98,123.45');

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }));

    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument();
    expect(http.count('POST /api/auth/logout')).toBe(1);
    expect(router.state.location.pathname).toBe('/login');
  });
});
