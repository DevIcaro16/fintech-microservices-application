import { Elysia, t } from "elysia";
import { tracePlugin } from "./middleware/trace";
import { rateLimiterPlugin } from "./middleware/rate-limiter";
import { authGuardPlugin } from "./middleware/auth-guard";
import { loggerPlugin } from "./middleware/logger";
import { forwardRequest } from "./proxy/proxy";

const NOTIFICATION_WS_URL = (
  process.env.NOTIFICATION_SERVICE_URL ??
  "http://notification-service.fintech.svc.cluster.local"
).replace(/^http/, "ws");

const wsUpstreams = new Map<string, WebSocket>();

const app = new Elysia()
  .use(tracePlugin)
  .use(rateLimiterPlugin)
  .use(authGuardPlugin)
  .use(loggerPlugin)
  .get("/healthz", () => ({ status: "ok" }))
  .ws("/ws", {
    query: t.Object({ transfer_id: t.String() }),
    open(ws) {
      const { transfer_id } = ws.data.query;
      const upstream = new WebSocket(
        `${NOTIFICATION_WS_URL}/ws?transfer_id=${transfer_id}`
      );
      upstream.onmessage = (evt) => {
        try { ws.send(evt.data as string); } catch {}
      };
      upstream.onerror = () => ws.close();
      upstream.onclose = () => ws.close();
      wsUpstreams.set(ws.id, upstream);
    },
    message(ws, message) {
      wsUpstreams
        .get(ws.id)
        ?.send(typeof message === "string" ? message : JSON.stringify(message));
    },
    close(ws) {
      const upstream = wsUpstreams.get(ws.id);
      upstream?.close();
      wsUpstreams.delete(ws.id);
    },
  })
  .get("/metrics", () => {
    const mem = process.memoryUsage();
    return new Response(
      [
        `# HELP api_gateway_heap_used_bytes Node heap used`,
        `# TYPE api_gateway_heap_used_bytes gauge`,
        `api_gateway_heap_used_bytes ${mem.heapUsed}`,
        `# HELP api_gateway_rss_bytes Process RSS`,
        `# TYPE api_gateway_rss_bytes gauge`,
        `api_gateway_rss_bytes ${mem.rss}`,
      ].join("\n"),
      { headers: { "content-type": "text/plain; version=0.0.4" } }
    );
  })
  .all("/*", async ({ request, traceId }) =>
    forwardRequest(request, traceId)
  )
  .listen(Number(process.env.PORT ?? 3000));

console.log(
  JSON.stringify({
    level: "info",
    service: "api-gateway",
    msg: `listening on :${app.server?.port}`,
    ts: new Date().toISOString(),
  })
);
