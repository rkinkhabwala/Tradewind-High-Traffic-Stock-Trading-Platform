/**
 * The app's one WebSocket to stream-service (through nginx and the gateway). Framework-free; React hooks sit on
 * top (realtime/hooks.tsx).
 *
 * - Auth: every connection starts with a fresh one-time ticket from POST /api/ws/ticket; nothing reusable is put
 *   in the URL.
 * - Reconnect: exponential backoff (250ms base, 5s cap) with full jitter, so thousands of clients that lost the
 *   same instance do not reconnect in lock-step. The attempt counter resets once the server says hello.
 * - Subscriptions are reference-counted across the app and re-sent after every reconnect.
 * - Liveness: the client pings every heartbeat interval (from hello); if nothing arrives for 45s the socket is
 *   treated as dead and replaced, even if the browser never reported it closed.
 * - After a reconnect, listeners are told so the app refetches REST state it may have missed (REST is the source
 *   of truth; user events are best effort).
 */

export interface Quote {
  symbol: string;
  bid: string;
  ask: string;
  last: string;
  bidSize: number;
  askSize: number;
  /** Only on the snapshot sent at subscribe time; kept across later ticks. */
  prevClose?: string;
  ts: string;
}

export interface OrderView {
  id: string;
  clientOrderId: string;
  symbol: string;
  side: 'BUY' | 'SELL';
  type: 'MARKET' | 'LIMIT';
  timeInForce: 'DAY' | 'GTC';
  qty: string;
  limitPrice: string | null;
  status: 'PENDING' | 'ACCEPTED' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELED' | 'REJECTED' | 'EXPIRED';
  filledQty: string;
  avgFillPrice: string | null;
  rejectReason: string | null;
  cancelRequestedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface OrderEvent {
  type: 'order';
  order: OrderView;
}

export interface PortfolioEvent {
  type: 'portfolio';
  reason: 'DEPOSIT' | 'WITHDRAWAL' | 'RESERVE' | 'RELEASE' | 'FILL';
  cashCents: number;
  cashReservedCents: number;
  buyingPowerCents: number;
  symbol: string | null;
  positionQty: string | null;
  occurredAt: string;
}

export type UserEvent = OrderEvent | PortfolioEvent;

export type ConnectionState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'stopped';

/** The subset of the browser WebSocket the client uses, so tests can supply a fake. */
export interface SocketLike {
  readonly readyState: number;
  onmessage: ((event: { data: unknown }) => void) | null;
  onclose: ((event: { code: number }) => void) | null;
  onerror: ((event: unknown) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export interface StreamDependencies {
  fetchTicket(): Promise<string>;
  createSocket(url: string): SocketLike;
  /** Absolute ws:// or wss:// URL for a ticket. */
  socketUrl(ticket: string): string;
  /** True when the error means the session is gone (stop instead of retrying). */
  isSessionEnded(error: unknown): boolean;
  random?: () => number;
}

export const BACKOFF_BASE_MS = 250;
export const BACKOFF_MAX_MS = 5_000;
export const SILENCE_TIMEOUT_MS = 45_000;
const DEFAULT_HEARTBEAT_MS = 15_000;
const OPEN = 1;

type Listener = () => void;

export class StreamClient {
  private readonly deps: StreamDependencies;
  private readonly random: () => number;
  private readonly refs = new Map<string, number>();
  private readonly quotes = new Map<string, Quote>();
  private readonly quoteListeners = new Map<string, Set<Listener>>();
  private readonly eventListeners = new Set<(event: UserEvent) => void>();
  private readonly stateListeners = new Set<Listener>();
  private readonly reconnectListeners = new Set<Listener>();

  private socket: SocketLike | null = null;
  private stateValue: ConnectionState = 'idle';
  private instance: string | null = null;
  private attempt = 0;
  private everOpened = false;
  private heartbeatTimer: ReturnType<typeof setInterval> | undefined;
  private silenceTimer: ReturnType<typeof setTimeout> | undefined;
  private reconnectTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(deps: StreamDependencies) {
    this.deps = deps;
    this.random = deps.random ?? Math.random;
  }

  get state(): ConnectionState {
    return this.stateValue;
  }

  /** The stream-service instance serving this client (from hello). */
  get instanceId(): string | null {
    return this.instance;
  }

  start(): void {
    if (this.stateValue === 'idle') {
      void this.connect();
    }
  }

  stop(): void {
    this.setState('stopped');
    clearTimeout(this.reconnectTimer);
    this.dropSocket(1000, 'bye');
  }

  // ---- subscriptions ----------------------------------------------------------------------------------------

  /** Reference-counted; returns the function that releases these symbols. */
  subscribe(symbols: string[]): () => void {
    const added: string[] = [];
    for (const symbol of symbols) {
      const count = this.refs.get(symbol) ?? 0;
      this.refs.set(symbol, count + 1);
      if (count === 0) {
        added.push(symbol);
      }
    }
    if (added.length > 0) {
      this.sendOp('subscribe', added);
    }
    let released = false;
    return () => {
      if (released) {
        return;
      }
      released = true;
      const removed: string[] = [];
      for (const symbol of symbols) {
        const count = (this.refs.get(symbol) ?? 0) - 1;
        if (count <= 0) {
          this.refs.delete(symbol);
          removed.push(symbol);
        } else {
          this.refs.set(symbol, count);
        }
      }
      if (removed.length > 0) {
        this.sendOp('unsubscribe', removed);
      }
    };
  }

  getQuote(symbol: string): Quote | undefined {
    return this.quotes.get(symbol);
  }

  onQuote(symbol: string, listener: Listener): () => void {
    let set = this.quoteListeners.get(symbol);
    if (!set) {
      set = new Set();
      this.quoteListeners.set(symbol, set);
    }
    set.add(listener);
    return () => {
      set.delete(listener);
    };
  }

  onEvent(listener: (event: UserEvent) => void): () => void {
    this.eventListeners.add(listener);
    return () => this.eventListeners.delete(listener);
  }

  onStateChange(listener: Listener): () => void {
    this.stateListeners.add(listener);
    return () => this.stateListeners.delete(listener);
  }

  /** Called after every reconnect (not the first connect): time to refetch REST state. */
  onReconnect(listener: Listener): () => void {
    this.reconnectListeners.add(listener);
    return () => this.reconnectListeners.delete(listener);
  }

  // ---- connection -------------------------------------------------------------------------------------------

  private async connect(): Promise<void> {
    this.setState(this.everOpened ? 'reconnecting' : 'connecting');
    let ticket: string;
    try {
      ticket = await this.deps.fetchTicket();
    } catch (error) {
      if (this.deps.isSessionEnded(error)) {
        this.stop();
      } else {
        this.scheduleReconnect();
      }
      return;
    }
    if (this.stateValue === 'stopped') {
      return;
    }
    const socket = this.deps.createSocket(this.deps.socketUrl(ticket));
    this.socket = socket;
    socket.onmessage = (event) => {
      if (this.socket === socket) {
        this.onMessage(event.data);
      }
    };
    socket.onclose = () => {
      if (this.socket === socket) {
        this.lost();
      }
    };
    socket.onerror = () => {
      // a close event follows
    };
    this.armSilenceTimer();
  }

  private onMessage(data: unknown): void {
    this.armSilenceTimer();
    let message: { type?: string } & Record<string, unknown>;
    try {
      message = JSON.parse(String(data));
    } catch {
      return;
    }
    switch (message.type) {
      case 'hello':
        this.onHello(message as unknown as { instanceId: string; heartbeatSeconds: number });
        break;
      case 'quote':
        this.onQuoteMessage(message as unknown as Quote);
        break;
      case 'order':
      case 'portfolio':
        this.eventListeners.forEach((listener) => listener(message as unknown as UserEvent));
        break;
      default:
        break;         // subscriptions, pong, error
    }
  }

  private onHello(hello: { instanceId: string; heartbeatSeconds: number }): void {
    this.instance = hello.instanceId;
    this.attempt = 0;
    const reconnected = this.everOpened;
    this.everOpened = true;
    this.setState('open');
    clearInterval(this.heartbeatTimer);
    const heartbeat = hello.heartbeatSeconds > 0 ? hello.heartbeatSeconds * 1000 : DEFAULT_HEARTBEAT_MS;
    this.heartbeatTimer = setInterval(() => this.send({ op: 'ping' }), heartbeat);
    if (this.refs.size > 0) {
      this.sendOp('subscribe', [...this.refs.keys()]);
    }
    if (reconnected) {
      this.reconnectListeners.forEach((listener) => listener());
    }
  }

  private onQuoteMessage(quote: Quote): void {
    const previous = this.quotes.get(quote.symbol);
    const merged: Quote = quote.prevClose === undefined && previous?.prevClose !== undefined
      ? { ...quote, prevClose: previous.prevClose }
      : quote;
    this.quotes.set(quote.symbol, merged);
    this.quoteListeners.get(quote.symbol)?.forEach((listener) => listener());
  }

  private lost(): void {
    this.dropSocket();
    if (this.stateValue !== 'stopped') {
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect(): void {
    this.setState('reconnecting');
    clearTimeout(this.reconnectTimer);
    const cap = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** this.attempt);
    const delay = Math.floor(this.random() * (this.attempt === 0 ? BACKOFF_BASE_MS : cap));
    this.attempt += 1;
    this.reconnectTimer = setTimeout(() => void this.connect(), delay);
  }

  private armSilenceTimer(): void {
    clearTimeout(this.silenceTimer);
    this.silenceTimer = setTimeout(() => {
      if (this.socket) {
        this.lost();      // silent for too long: assume the connection is dead, whatever the browser thinks
      }
    }, SILENCE_TIMEOUT_MS);
  }

  private dropSocket(code?: number, reason?: string): void {
    clearInterval(this.heartbeatTimer);
    clearTimeout(this.silenceTimer);
    const socket = this.socket;
    this.socket = null;
    if (socket) {
      socket.onmessage = null;
      socket.onclose = null;
      socket.onerror = null;
      try {
        socket.close(code ?? 4002, reason ?? 'reconnecting');
      } catch {
        // already closed
      }
    }
  }

  private sendOp(op: 'subscribe' | 'unsubscribe', symbols: string[]): void {
    this.send({ op, symbols });
  }

  private send(message: object): void {
    if (this.socket && this.socket.readyState === OPEN && this.stateValue === 'open') {
      this.socket.send(JSON.stringify(message));
    }
  }

  private setState(state: ConnectionState): void {
    if (this.stateValue !== state) {
      this.stateValue = state;
      this.stateListeners.forEach((listener) => listener());
    }
  }
}
