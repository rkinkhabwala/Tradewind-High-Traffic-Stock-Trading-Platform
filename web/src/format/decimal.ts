/**
 * Exact decimal arithmetic for the few calculations the client does with money: live market value and day change
 * of a position. Quantities (6 places) and prices (4 places) arrive as strings and are handled as scaled BigInts;
 * results are rounded half-even to whole cents, exactly as portfolio-service rounds them.
 */

export const QTY_SCALE = 6;
export const PRICE_SCALE = 4;
const PRODUCT_TO_CENTS = 10n ** BigInt(QTY_SCALE + PRICE_SCALE - 2);

/** "237.3" at scale 4 -> 2373000n. Rejects anything that is not a plain decimal or has too many places. */
export function parseScaled(value: string, scale: number): bigint {
  const match = /^(-)?(\d+)(?:\.(\d+))?$/.exec(value.trim());
  if (!match) {
    throw new RangeError(`not a decimal: ${value}`);
  }
  const [, sign, whole, fraction = ''] = match;
  if (fraction.replace(/0+$/, '').length > scale) {
    throw new RangeError(`${value} has more than ${scale} decimal places`);
  }
  const scaled = BigInt(whole) * 10n ** BigInt(scale) + BigInt(fraction.padEnd(scale, '0') || '0');
  return sign ? -scaled : scaled;
}

/** n / d rounded half to even (d > 0). */
export function divideHalfEven(n: bigint, d: bigint): bigint {
  const negative = n < 0n;
  const abs = negative ? -n : n;
  let q = abs / d;
  const r = abs % d;
  if (r * 2n > d || (r * 2n === d && q % 2n === 1n)) {
    q += 1n;
  }
  return negative ? -q : q;
}

/** qty x price in cents, rounded half-even. */
export function notionalCents(qty: string, price: string): number {
  const product = parseScaled(qty, QTY_SCALE) * parseScaled(price, PRICE_SCALE);
  return Number(divideHalfEven(product, PRODUCT_TO_CENTS));
}

/** qty x (last - prevClose) in cents, rounded half-even: the position's change today. */
export function changeCents(qty: string, last: string, prevClose: string): number {
  const diff = parseScaled(last, PRICE_SCALE) - parseScaled(prevClose, PRICE_SCALE);
  return Number(divideHalfEven(parseScaled(qty, QTY_SCALE) * diff, PRODUCT_TO_CENTS));
}

/** Percentage for display only (not money): 2 decimals, e.g. "+1.25%". */
export function formatPercent(part: number, base: number): string {
  if (base === 0) {
    return '0.00%';
  }
  const pct = (part / base) * 100;
  return `${pct > 0 ? '+' : ''}${pct.toFixed(2)}%`;
}

/** Quantity for display: "10.000000" -> "10", "0.123400" -> "0.1234". */
export function formatQty(qty: string): string {
  return qty.includes('.') ? qty.replace(/\.?0+$/, '') : qty;
}

/** A scaled BigInt back to a plain decimal string with exactly {@code scale} places. */
export function formatScaled(value: bigint, scale: number): string {
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(scale + 1, '0');
  const whole = digits.slice(0, digits.length - scale);
  const fraction = digits.slice(digits.length - scale);
  return `${negative ? '-' : ''}${whole}${scale > 0 ? `.${fraction}` : ''}`;
}

/** "12.5" -> 1250 cents; null if not a plain amount with at most 2 decimals. */
export function parseDollarsToCents(input: string): number | null {
  if (!/^\d+(\.\d{0,2})?$/.test(input.trim())) {
    return null;
  }
  return Number(parseScaled(input.trim().replace(/\.$/, ''), 2));
}

/**
 * Shares a dollar amount buys at {@code price}, rounded DOWN to 6 decimal places (never more than the amount).
 * Client-side estimate: the order is placed for this quantity, and the fill price decides the final cost.
 */
export function sharesForDollars(cents: number, price: string): string {
  const price4 = parseScaled(price, PRICE_SCALE);
  if (price4 <= 0n) {
    throw new RangeError('price must be positive');
  }
  // qty = (cents / 100) / (price4 / 10^4); at scale 6: cents x 10^8 / price4, floored
  return formatScaled((BigInt(cents) * 10n ** 8n) / price4, QTY_SCALE);
}

/** A valid order quantity: positive, at most 6 decimal places. */
export function isValidQty(qty: string): boolean {
  return /^\d+(\.\d{1,6})?$/.test(qty) && parseScaled(qty, QTY_SCALE) > 0n;
}

/** A valid limit price: positive whole cents (the server rejects sub-cent limits). */
export function isValidLimitPrice(price: string): boolean {
  return /^\d+(\.\d{1,2})?$/.test(price) && parseScaled(price, 2) > 0n;
}
