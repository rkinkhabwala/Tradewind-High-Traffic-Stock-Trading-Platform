import { ApiError } from '../api/client';

/** User-facing text for the server's error and rejection codes. */
const MESSAGES: Record<string, string> = {
  INSUFFICIENT_BALANCE: 'Not enough buying power (or shares) for this order.',
  MARKET_CLOSED: 'The market is closed. Only Good-til-canceled limit orders are accepted now.',
  PRICE_OUT_OF_BAND: 'That limit price is too far from the current price.',
  INVALID_TICK: 'Limit prices must be in whole cents.',
  INVALID_QUANTITY: 'Enter a quantity greater than zero.',
  INVALID_ORDER: 'This order is not valid.',
  UNKNOWN_SYMBOL: 'Unknown symbol.',
  RESERVATION_TIMEOUT: 'The order could not be confirmed in time and was canceled. Nothing was charged.',
  RESERVATION_RELEASED: 'The order could not be confirmed and was canceled. Nothing was charged.',
  ORDER_NOT_OPEN: 'This order can no longer be canceled.',
  COLLAR_EXCEEDED: 'Canceled: the price moved more than the market-order collar allows.',
  USER_REQUESTED: 'Canceled at your request.',
};

export function reasonText(code: string | null | undefined): string | null {
  return code ? (MESSAGES[code] ?? code.replaceAll('_', ' ').toLowerCase()) : null;
}

export function orderErrorText(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 429) {
      return 'Too many orders at once. Wait a moment and try again.';
    }
    return reasonText(error.code) ?? error.detail ?? 'The order could not be placed.';
  }
  return 'Network problem. Your order may not have been placed; check Orders before retrying.';
}
