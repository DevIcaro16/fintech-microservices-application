import { Elysia, t } from "elysia";
import { register, unregister, connectionCount } from "./websocket/manager";
import { startConsumer, stopConsumer } from "./kafka/consumer";

const app = new Elysia()
  .ws("/ws", {
    query: t.Object({ transfer_id: t.String() }),
    open(ws) {
      const { transfer_id } = ws.data.query;
      register(transfer_id, ws.raw);
    },
    close(ws) {
      const { transfer_id } = ws.data.query;
      unregister(transfer_id, ws.raw);
    },
    message(_ws, _msg) {
      // ping/pong — sem processamento
    },
  })
  .get("/healthz", () => ({ status: "ok", connections: connectionCount() }))
  .get("/metrics", () => {
    const mem = process.memoryUsage();
    return new Response(
      [
        `# HELP notification_ws_connections_total Active WebSocket connections`,
        `# TYPE notification_ws_connections_total gauge`,
        `notification_ws_connections_total ${connectionCount()}`,
        `# HELP notification_heap_used_bytes Node heap used`,
        `# TYPE notification_heap_used_bytes gauge`,
        `notification_heap_used_bytes ${mem.heapUsed}`,
      ].join("\n"),
      { headers: { "content-type": "text/plain; version=0.0.4" } }
    );
  })
  .listen(Number(process.env.PORT ?? 3001));

console.log(
  JSON.stringify({
    level: "info",
    service: "notification-service",
    msg: `listening on :${app.server?.port}`,
    ts: new Date().toISOString(),
  })
);

await startConsumer();

process.on("SIGTERM", async () => {
  await stopConsumer();
  process.exit(0);
});
