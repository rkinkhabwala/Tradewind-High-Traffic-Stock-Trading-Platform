import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { addSymbol, createWatchlist, DEFAULT_LIST_NAME, listWatchlists, removeSymbol, type Watchlist } from './api';

export function useWatchlists() {
  return useQuery({ queryKey: ['watchlists'], queryFn: ({ signal }) => listWatchlists(signal) });
}

/** Adds to / removes from the first list, creating "My First List" on first use. */
export function useToggleWatch(symbol: string) {
  const queryClient = useQueryClient();
  const lists = useWatchlists();
  const first: Watchlist | undefined = lists.data?.[0];
  const watching = Boolean(first?.symbols.includes(symbol));

  const toggle = useMutation({
    mutationFn: async () => {
      let list = first;
      if (!list) {
        list = await createWatchlist(DEFAULT_LIST_NAME, crypto.randomUUID());
      }
      return watching
        ? removeSymbol(list.id, symbol, crypto.randomUUID())
        : addSymbol(list.id, symbol, crypto.randomUUID());
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['watchlists'] }),
  });
  return { watching, toggle, loading: lists.isPending };
}
