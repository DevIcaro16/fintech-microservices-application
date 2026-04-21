import { resolveUpstream } from "../config/routes";

export async function forwardRequest(
  request: Request,
  traceId: string
): Promise<Response> {
  const url = new URL(request.url);
  const upstream = resolveUpstream(url.pathname);

  if (!upstream) {
    return new Response(JSON.stringify({ error: "not found" }), {
      status: 404,
      headers: { "content-type": "application/json" },
    });
  }

  const targetUrl = upstream + url.pathname + url.search;

  const headers = new Headers(request.headers);
  headers.set("x-trace-id", traceId);
  headers.delete("host");

  const upstreamRes = await fetch(targetUrl, {
    method: request.method,
    headers,
    body:
      request.method !== "GET" && request.method !== "HEAD"
        ? request.body
        : undefined,
  });

  const resHeaders = new Headers(upstreamRes.headers);
  resHeaders.set("x-trace-id", traceId);

  return new Response(upstreamRes.body, {
    status: upstreamRes.status,
    headers: resHeaders,
  });
}
