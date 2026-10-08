import { useQuery } from '@tanstack/react-query';
import { api } from '../api/client';
import type { PortfolioView } from './types';

export function usePortfolio() {
  return useQuery({
    queryKey: ['portfolio'],
    queryFn: ({ signal }) => api<PortfolioView>('/api/portfolio', { signal }),
  });
}
