import { useEffect } from 'react';
import { formatQty } from '../format/decimal';
import { formatPrice } from '../format/money';
import { reasonText } from '../orders/messages';
import { useStreamClient } from '../realtime/RealtimeProvider';
import type { OrderView } from '../realtime/StreamClient';
import { useToast } from './ToastProvider';

export function orderToast(order: OrderView): { message: string; tone: 'success' | 'error' | 'info' } | null {
  const verb = order.side === 'BUY' ? 'Bought' : 'Sold';
  const qty = formatQty(order.filledQty);
  switch (order.status) {
    case 'FILLED':
      return { message: `${verb} ${qty} ${order.symbol} at ${order.avgFillPrice ? formatPrice(order.avgFillPrice) : '—'}`,
        tone: 'success' };
    case 'PARTIALLY_FILLED':
      return { message: `${verb} ${qty} of ${formatQty(order.qty)} ${order.symbol} so far`, tone: 'info' };
    case 'REJECTED':
      return { message: `${order.symbol} order rejected. ${reasonText(order.rejectReason) ?? ''}`.trim(), tone: 'error' };
    case 'CANCELED':
      return { message: `${order.symbol} order canceled${Number(order.filledQty) > 0 ? ` after ${qty} filled` : ''}. ${
        order.rejectReason === 'COLLAR_EXCEEDED' ? reasonText(order.rejectReason) : ''}`.trim(), tone: 'info' };
    case 'EXPIRED':
      return { message: `${order.symbol} order expired at the close`, tone: 'info' };
    default:
      return null;      // PENDING/ACCEPTED: the placing screen already confirmed it
  }
}

/** Live toasts for the user's order events (fills, rejections, cancels, expiries), pushed by stream-service. */
export function OrderToasts() {
  const client = useStreamClient();
  const toast = useToast();
  useEffect(() => {
    if (!client) {
      return undefined;
    }
    return client.onEvent((event) => {
      if (event.type !== 'order') {
        return;
      }
      const shown = orderToast(event.order);
      if (shown) {
        toast.show(shown.message, { tone: shown.tone, tag: `order-${event.order.status}-${event.order.id}` });
      }
    });
  }, [client, toast]);
  return null;
}
