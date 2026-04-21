import type { ServerWebSocket } from "bun";

type WS = Pick<ServerWebSocket<unknown>, "send" | "readyState">;

const connections = new Map<string, Set<WS>>();

export function register(transferId: string, ws: WS): void {
  if (!connections.has(transferId)) connections.set(transferId, new Set());
  connections.get(transferId)!.add(ws);
}

export function unregister(transferId: string, ws: WS): void {
  const sockets = connections.get(transferId);
  if (!sockets) return;
  sockets.delete(ws);
  if (sockets.size === 0) connections.delete(transferId);
}

export function broadcast(transferId: string, payload: object): void {
  const sockets = connections.get(transferId);
  if (!sockets) return;
  const message = JSON.stringify(payload);
  for (const ws of sockets) {
    if (ws.readyState === 1) ws.send(message);
  }
}

export function connectionCount(): number {
  let count = 0;
  for (const set of connections.values()) count += set.size;
  return count;
}

export function clear(): void {
  connections.clear();
}
