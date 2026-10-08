import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import styles from './Toast.module.css';

export type ToastTone = 'info' | 'success' | 'error';

export interface Toast {
  id: number;
  message: string;
  tone: ToastTone;
  /** For tests and E2E timing, e.g. the order status that produced it. */
  tag?: string;
}

interface ToastApi {
  show(message: string, options?: { tone?: ToastTone; tag?: string; durationMs?: number }): void;
}

const ToastContext = createContext<ToastApi | null>(null);
const DEFAULT_DURATION_MS = 6000;
const MAX_VISIBLE = 4;

/** Transient notifications, announced politely to screen readers. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setToasts((all) => all.filter((t) => t.id !== id)), []);

  const show = useCallback<ToastApi['show']>((message, options = {}) => {
    const id = nextId.current++;
    setToasts((all) => [...all, { id, message, tone: options.tone ?? 'info', tag: options.tag }].slice(-MAX_VISIBLE));
    setTimeout(() => dismiss(id), options.durationMs ?? DEFAULT_DURATION_MS);
  }, [dismiss]);

  const value = useMemo(() => ({ show }), [show]);
  return (
    <ToastContext.Provider value={value}>
      {children}
      <div className={styles.region} aria-live="polite" aria-label="Notifications">
        {toasts.map((t) => (
          <div key={t.id} className={`${styles.toast} ${styles[t.tone]}`} data-testid="toast" data-tag={t.tag}>
            <span>{t.message}</span>
            <button type="button" className={styles.close} aria-label="Dismiss" onClick={() => dismiss(t.id)}>
              ×
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastApi {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used inside ToastProvider');
  }
  return context;
}
