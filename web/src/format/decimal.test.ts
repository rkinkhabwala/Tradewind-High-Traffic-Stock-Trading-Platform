import { describe, expect, it } from 'vitest';
import { changeCents, divideHalfEven, formatPercent, formatQty, notionalCents, parseScaled } from './decimal';

describe('decimal', () => {
  it('parses scaled decimals exactly', () => {
    expect(parseScaled('237.3', 4)).toBe(2_373_000n);
    expect(parseScaled('-0.0001', 4)).toBe(-1n);
    expect(parseScaled('10', 6)).toBe(10_000_000n);
    expect(() => parseScaled('1.00001', 4)).toThrow(RangeError);
    expect(() => parseScaled('1e3', 4)).toThrow(RangeError);
  });

  it('rounds half to even', () => {
    expect(divideHalfEven(25n, 10n)).toBe(2n);
    expect(divideHalfEven(35n, 10n)).toBe(4n);
    expect(divideHalfEven(26n, 10n)).toBe(3n);
    expect(divideHalfEven(-25n, 10n)).toBe(-2n);
  });

  it('values positions in cents without floating point', () => {
    expect(notionalCents('10.000000', '237.3000')).toBe(237_300);
    // 0.1 x 0.1 would be 0.010000000000000002 in floating point
    expect(notionalCents('0.100000', '0.1000')).toBe(1);
    // 0.333333 x 199.9900 = 66.663266...; 1.5 cents rounds to 2 (even)
    expect(notionalCents('0.333333', '199.9900')).toBe(6666);
    expect(notionalCents('0.015000', '1.0000')).toBe(2);
    expect(notionalCents('0.025000', '1.0000')).toBe(2);
  });

  it('computes the change today from the previous close', () => {
    expect(changeCents('10.000000', '210.0000', '200.0000')).toBe(10_000);
    expect(changeCents('3.000000', '199.5000', '200.0000')).toBe(-150);
  });

  it('formats percentages and quantities for display', () => {
    expect(formatPercent(250, 10_000)).toBe('+2.50%');
    expect(formatPercent(-250, 10_000)).toBe('-2.50%');
    expect(formatPercent(5, 0)).toBe('0.00%');
    expect(formatQty('10.000000')).toBe('10');
    expect(formatQty('0.123400')).toBe('0.1234');
  });
});
