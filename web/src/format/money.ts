/**
 * Display formatting for amounts that arrive as integer cents. Only integer arithmetic on the cents: no
 * floating-point division is involved in turning 123456 into "$1,234.56".
 */
const grouping = new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 });

export function formatCents(cents: number, options: { signed?: boolean } = {}): string {
  if (!Number.isSafeInteger(cents)) {
    throw new RangeError(`not an integer number of cents: ${cents}`);
  }
  const negative = cents < 0;
  const abs = Math.abs(cents);
  const dollars = Math.trunc(abs / 100);           // exact for safe integers
  const rest = abs % 100;
  const body = `$${grouping.format(dollars)}.${rest.toString().padStart(2, '0')}`;
  if (negative) {
    return `-${body}`;
  }
  return options.signed && cents > 0 ? `+${body}` : body;
}

/** Prices arrive as decimal strings with 4 places ("237.3000"); show at least 2, keep sub-cent digits if any. */
export function formatPrice(price: string): string {
  const [whole, fraction = ''] = price.split('.');
  const trimmed = fraction.replace(/0+$/, '').padEnd(2, '0');
  const negative = whole.startsWith('-');
  const digits = negative ? whole.slice(1) : whole;
  const grouped = digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${negative ? '-' : ''}$${grouped}.${trimmed}`;
}
