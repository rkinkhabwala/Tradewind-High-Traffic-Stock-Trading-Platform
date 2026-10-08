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
