import { describe, expect, it } from 'vitest';
import { formatCents, formatPrice } from './money';

describe('formatCents', () => {
  it.each([
    [0, '$0.00'],
    [5, '$0.05'],
    [123456, '$1,234.56'],
    [-9_800_000, '-$98,000.00'],
    [9_007_199_254_740_991, '$90,071,992,547,409.91'],
  ])('%d -> %s', (cents, text) => expect(formatCents(cents)).toBe(text));

  it('can show a plus sign for gains', () => {
    expect(formatCents(2500, { signed: true })).toBe('+$25.00');
    expect(formatCents(-2500, { signed: true })).toBe('-$25.00');
  });

  it('refuses non-integer cents instead of rounding them', () => {
    expect(() => formatCents(1.5)).toThrow(RangeError);
  });
});

describe('formatPrice', () => {
  it.each([
    ['237.3000', '$237.30'],
    ['1234.5000', '$1,234.50'],
    ['0.1234', '$0.1234'],
    ['12', '$12.00'],
  ])('%s -> %s', (price, text) => expect(formatPrice(price)).toBe(text));
});
