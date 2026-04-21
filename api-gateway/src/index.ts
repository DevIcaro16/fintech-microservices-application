import { Elysia } from "elysia";
import { tracePlugin } from "./middleware/trace";
import { rateLimiterPlugin } from "./middleware/rate-limiter";
import { authGuardPlugin } from "./middleware/auth-guard";
import { loggerPlugin } from "./middleware/logger";
import { forwardRequest } from "./proxy/proxy";

const app = new Elysia()
  .use(tracePlugin)
  .use(rateLimiterPlugin)
  .use(authGuardPlugin)
  .use(loggerPlugin)
  .get("/healthz", () => ({ status: "ok" }))
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
