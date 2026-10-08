import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider, type RouteObject } from 'react-router';
import { ApiError } from './api/client';
import { AuthProvider } from './auth/AuthProvider';
import { RequireAuth } from './auth/RequireAuth';
import { RealtimeProvider } from './realtime/RealtimeProvider';
import { AppLayout } from './layout/AppLayout';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { OrdersPage } from './pages/OrdersPage';
import { SymbolPage } from './pages/SymbolPage';
import { OrderToasts } from './toast/OrderToasts';
import { ToastProvider } from './toast/ToastProvider';
import { RegisterPage } from './pages/RegisterPage';

export const routes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  { path: '/register', element: <RegisterPage /> },
  {
    element: (
      <RequireAuth>
        <AppLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <HomePage /> },
      { path: 'stocks/:symbol', element: <SymbolPage /> },
      { path: 'orders', element: <OrdersPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 10_000,
        // Client errors (other than an expired session, handled by the API client) will not fix themselves.
        retry: (failures, error) =>
          failures < 3 && !(error instanceof ApiError && error.status >= 400 && error.status < 500),
      },
    },
  });
}

const router = createBrowserRouter(routes);
const queryClient = createQueryClient();

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RealtimeProvider>
          <ToastProvider>
            <OrderToasts />
            <RouterProvider router={router} />
          </ToastProvider>
        </RealtimeProvider>
      </AuthProvider>
    </QueryClientProvider>
  );
}
