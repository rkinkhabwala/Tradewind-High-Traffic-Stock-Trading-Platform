import { useState } from 'react';
import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import { formatQty } from '../format/decimal';
import { formatPrice } from '../format/money';
import { cancelOrder, listOrders, OPEN_STATUSES, type OrderStatus, type OrderView } from '../orders/api';
import { orderErrorText, reasonText } from '../orders/messages';
import { useToast } from '../toast/ToastProvider';
import styles from './OrdersPage.module.css';

const FILTERS: { label: string; status?: OrderStatus }[] = [
  { label: 'All' },
  { label: 'Open', status: 'ACCEPTED' },
  { label: 'Partially filled', status: 'PARTIALLY_FILLED' },
  { label: 'Filled', status: 'FILLED' },
  { label: 'Canceled', status: 'CANCELED' },
  { label: 'Rejected', status: 'REJECTED' },
  { label: 'Expired', status: 'EXPIRED' },
];

const STATUS_LABEL: Record<OrderStatus, string> = {
  PENDING: 'Pending', ACCEPTED: 'Open', PARTIALLY_FILLED: 'Partially filled', FILLED: 'Filled',
  CANCELED: 'Canceled', REJECTED: 'Rejected', EXPIRED: 'Expired',
};

const when = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' });

function CancelButton({ order }: { order: OrderView }) {
  const queryClient = useQueryClient();
  const toast = useToast();
  // One key per order: repeated clicks or retries ask for the same cancel.
  const [key] = useState(() => crypto.randomUUID());
  const cancel = useMutation({
    mutationFn: () => cancelOrder(order.id, key),
    onSuccess: () => {
      toast.show(`Cancel requested for ${order.symbol}`, { tone: 'info' });
      void queryClient.invalidateQueries({ queryKey: ['orders'] });
    },
    onError: (e) => toast.show(orderErrorText(e), { tone: 'error' }),
  });
  if (order.cancelRequestedAt) {
    return <span className={styles.muted}>Canceling…</span>;
  }
  return (
    <button type="button" className={styles.cancel} disabled={cancel.isPending} onClick={() => cancel.mutate()}>
      Cancel
    </button>
  );
}

export function OrdersPage() {
  const [filter, setFilter] = useState(0);
  const status = FILTERS[filter].status;
  const orders = useInfiniteQuery({
    queryKey: ['orders', status ?? 'ALL'],
    queryFn: ({ pageParam, signal }) => listOrders({ status, cursor: pageParam }, signal),
    initialPageParam: null as string | null,
    getNextPageParam: (page) => page.nextCursor,
  });
  const items = orders.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <section aria-label="Orders">
      <h1 className={styles.title}>Orders</h1>
      <div className={styles.filters} role="tablist" aria-label="Status">
        {FILTERS.map((f, i) => (
          <button key={f.label} type="button" role="tab" aria-selected={i === filter}
                  className={`${styles.filter} ${i === filter ? styles.filterActive : ''}`} onClick={() => setFilter(i)}>
            {f.label}
          </button>
        ))}
      </div>
      {orders.isPending && <p className={styles.muted}>Loading orders…</p>}
      {orders.isError && <p role="alert">Could not load orders.</p>}
      {orders.isSuccess && items.length === 0 && <p className={styles.muted}>No orders.</p>}
      {items.length > 0 && (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Stock</th>
              <th>Order</th>
              <th className={styles.num}>Filled</th>
              <th className={styles.num}>Price</th>
              <th>Status</th>
              <th>Placed</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          <tbody>
            {items.map((o) => (
              <tr key={o.id} data-testid={`order-${o.id}`} data-status={o.status}>
                <td><Link to={`/stocks/${o.symbol}`} className={styles.symbol}>{o.symbol}</Link></td>
                <td>
                  {o.side === 'BUY' ? 'Buy' : 'Sell'} {formatQty(o.qty)} · {o.type === 'MARKET' ? 'Market' : `Limit ${formatPrice(o.limitPrice!)}`}
                  {o.type === 'LIMIT' && <span className={styles.muted}> · {o.timeInForce === 'GTC' ? 'GTC' : 'Day'}</span>}
                </td>
                <td className={styles.num}>{formatQty(o.filledQty)}</td>
                <td className={styles.num}>{o.avgFillPrice ? formatPrice(o.avgFillPrice) : '—'}</td>
                <td>
                  <span className={`${styles.status} ${styles[o.status]}`}>{STATUS_LABEL[o.status]}</span>
                  {o.rejectReason && o.status !== 'FILLED' && (
                    <span className={styles.reason}>{reasonText(o.rejectReason)}</span>
                  )}
                </td>
                <td className={styles.muted}>{when.format(new Date(o.createdAt))}</td>
                <td>{OPEN_STATUSES.includes(o.status) && o.status !== 'PENDING' && <CancelButton order={o} />}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {orders.hasNextPage && (
        <button type="button" className={styles.more} disabled={orders.isFetchingNextPage}
                onClick={() => void orders.fetchNextPage()}>
          {orders.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      )}
    </section>
  );
}
