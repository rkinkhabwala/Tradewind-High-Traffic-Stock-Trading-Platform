import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, vi } from 'vitest';
import { clearSession } from '../api/client';

afterEach(() => {
  cleanup();
  clearSession();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

// jsdom has no canvas; charts are covered by the browser E2E tests.
vi.mock('lightweight-charts', () => {
  const series = { setData: vi.fn(), update: vi.fn(), applyOptions: vi.fn() };
  return {
    AreaSeries: {},
    CandlestickSeries: {},
    HistogramSeries: {},
    ColorType: { Solid: 'solid' },
    TickMarkType: { Year: 0, Month: 1, DayOfMonth: 2, Time: 3, TimeWithSeconds: 4 },
    createChart: vi.fn(() => ({
      addSeries: vi.fn(() => series),
      timeScale: () => ({ fitContent: vi.fn() }),
      applyOptions: vi.fn(),
      remove: vi.fn(),
    })),
  };
});
