import { describe, expect, it } from 'vitest';
import type { Time } from 'lightweight-charts';
import { tickLabel } from './ValueChart';

const at = (iso: string) => (Date.parse(iso) / 1000) as Time;

describe('chart labels', () => {
  it('shows market (New York) time, not UTC or the viewer zone', () => {
    expect(tickLabel(at('2026-01-05T14:30:00Z'), 3)).toBe('9:30 AM');
    expect(tickLabel(at('2026-01-05T21:00:00Z'), 3)).toBe('4:00 PM');
    expect(tickLabel(at('2026-07-06T13:30:00Z'), 3)).toBe('9:30 AM');      // daylight saving time
    expect(tickLabel(at('2026-01-06T03:00:00Z'), 2)).toBe('Jan 5');        // still Jan 5 in New York
  });
});
