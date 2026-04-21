import { describe, it, expect, beforeEach } from "bun:test";
import { register, unregister, broadcast, connectionCount, clear } from "../websocket/manager";

function fakeWs(messages: string[]) {
  return {
    readyState: 1,
    send: (msg: string) => { messages.push(msg); },
  };
}

describe("WebSocket Manager", () => {
  beforeEach(() => clear());

  it("register increases connectionCount", () => {
    const ws = fakeWs([]);
    register("tx-1", ws as any);
    expect(connectionCount()).toBe(1);
  });

  it("unregister decreases connectionCount", () => {
    const messages: string[] = [];
    const ws = fakeWs(messages);
    register("tx-1", ws as any);
    unregister("tx-1", ws as any);
    expect(connectionCount()).toBe(0);
  });

  it("broadcast sends message to all sockets for transferId", () => {
    const m1: string[] = [];
    const m2: string[] = [];
    register("tx-1", fakeWs(m1) as any);
    register("tx-1", fakeWs(m2) as any);
    broadcast("tx-1", { status: "COMPLETED" });
    expect(m1).toHaveLength(1);
    expect(m2).toHaveLength(1);
    expect(JSON.parse(m1[0])).toEqual({ status: "COMPLETED" });
  });

  it("broadcast ignores closed sockets", () => {
    const messages: string[] = [];
    const ws = { readyState: 3, send: (msg: string) => messages.push(msg) };
    register("tx-2", ws as any);
    broadcast("tx-2", { status: "FAILED" });
    expect(messages).toHaveLength(0);
  });

  it("broadcast to unknown transferId does nothing", () => {
    expect(() => broadcast("unknown", { status: "OK" })).not.toThrow();
  });
});
