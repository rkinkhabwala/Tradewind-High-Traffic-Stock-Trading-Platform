import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import * as authApi from '../api/auth';
import { onSessionEnded, refreshSession } from '../api/client';

export type AuthStatus = 'checking' | 'authenticated' | 'anonymous';

interface AuthContextValue {
  status: AuthStatus;
  login(email: string, password: string): Promise<void>;
  register(email: string, password: string): Promise<void>;
  logout(): Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * Session state. On start-up the refresh cookie (if any) is exchanged for an access token, so a reload keeps the
 * user signed in without any token in browser storage.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [status, setStatus] = useState<AuthStatus>('checking');
  const queryClient = useQueryClient();

  useEffect(() => {
    let active = true;
    void refreshSession().then((ok) => {
      if (active) {
        setStatus(ok ? 'authenticated' : 'anonymous');
      }
    });
    const unsubscribe = onSessionEnded(() => {
      queryClient.clear();
      setStatus('anonymous');
    });
    return () => {
      active = false;
      unsubscribe();
    };
  }, [queryClient]);

  const login = useCallback(async (email: string, password: string) => {
    await authApi.login(email, password);
    setStatus('authenticated');
  }, []);

  const register = useCallback(async (email: string, password: string) => {
    await authApi.register(email, password);
    await authApi.login(email, password);
    setStatus('authenticated');
  }, []);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } finally {
      queryClient.clear();
      setStatus('anonymous');
    }
  }, [queryClient]);

  const value = useMemo(() => ({ status, login, register, logout }), [status, login, register, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
