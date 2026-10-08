import { useConnection } from '../realtime/RealtimeProvider';
import styles from './ConnectionBadge.module.css';

const LABELS = { idle: 'Offline', connecting: 'Connecting…', open: 'Live', reconnecting: 'Reconnecting…', stopped: 'Offline' };

/** Live-data indicator; the title names the stream-service instance (useful when testing failover). */
export function ConnectionBadge() {
  const { state, instanceId } = useConnection();
  return (
    <span className={`${styles.badge} ${state === 'open' ? styles.live : styles.down}`} role="status"
          data-state={state} data-instance={instanceId ?? undefined}
          title={instanceId ? `Streaming from ${instanceId}` : undefined}>
      <span className={styles.dot} aria-hidden="true" />
      {LABELS[state]}
    </span>
  );
}
