import { describe, expect, it } from 'vitest';
import { formatScaled, isValidLimitPrice, isValidQty, parseDollarsToCents, sharesForDollars } from './decimal';

describe('dollar orders', () => {
  it('converts dollars to shares rounded down to 6 decimals, never over the amount', () => {
    expect(sharesForDollars(10_000, '200.0100')).toBe('0.499975');     // 100 / 200.01 = 0.49997500...
    expect(sharesForDollars(10_000, '3.0000')).toBe('33.333333');
    expect(sharesForDollars(10_000, '100.0000')).toBe('1.000000');
    expect(sharesForDollars(1, '5000.0000')).toBe('0.000002');
  });

  it('parses dollar amounts to cents exactly', () => {
    expect(parseDollarsToCents('12.5')).toBe(1250);
    expect(parseDollarsToCents('0.10')).toBe(10);
    expect(parseDollarsToCents('100.')).toBe(10_000);
    expect(parseDollarsToCents('1.005')).toBeNull();
    expect(parseDollarsToCents('-1')).toBeNull();
    expect(parseDollarsToCents('abc')).toBeNull();
  });

  it('validates quantities and limit prices like the server', () => {
    expect(isValidQty('1')).toBe(true);
    expect(isValidQty('0.000001')).toBe(true);
    expect(isValidQty('0.0000001')).toBe(false);
    expect(isValidQty('0')).toBe(false);
    expect(isValidLimitPrice('187.25')).toBe(true);
    expect(isValidLimitPrice('187.255')).toBe(false);
    expect(isValidLimitPrice('0')).toBe(false);
  });

  it('formats scaled integers', () => {
    expect(formatScaled(1_234_567n, 6)).toBe('1.234567');
    expect(formatScaled(5n, 6)).toBe('0.000005');
    expect(formatScaled(-150n, 2)).toBe('-1.50');
  });
});
