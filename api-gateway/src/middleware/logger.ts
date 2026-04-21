import { Elysia } from "elysia";

export const loggerPlugin = new Elysia({ name: "logger" }).onAfterHandle(
  { as: "global" },
  ({ request, set }) => {
    const url = new URL(request.url);
    console.log(
      JSON.stringify({
        level: "info",
        service: "api-gateway",
        method: request.method,
        path: url.pathname,
        status: set.status,
        trace_id: request.headers.get("x-trace-id"),
        ts: new Date().toISOString(),
      })
    );
  }
);
