import type { SocketLike } from '../realtime/StreamClient';

/** A WebSocket the test drives: open it, push server messages, close it from the server side. */
export class FakeSocket implements SocketLike {
  readyState = 0;
  readonly sent: Record<string, unknown>[] = [];
  closedByClient: number | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;
  readonly url: string;

  constructor(url: string) {
    this.url = url;
  }

  open(): this {
    this.readyState = 1;
    return this;
  }

  receive(message: object): void {
    this.onmessage?.({ data: JSON.stringify(message) });
  }

  hello(instanceId = 'inst-1', heartbeatSeconds = 15): this {
    this.open();
    this.receive({ type: 'hello', instanceId, heartbeatSeconds });
    return this;
  }

  serverClose(code = 1006): void {
    this.readyState = 3;
    this.onclose?.({ code });
  }

  send(data: string): void {
    this.sent.push(JSON.parse(data));
  }

  close(code?: number): void {
    this.readyState = 3;
    this.closedByClient = code ?? 1000;
  }

  ops(op: string): unknown[] {
    return this.sent.filter((m) => m.op === op).map((m) => m.symbols ?? true);
  }
}
