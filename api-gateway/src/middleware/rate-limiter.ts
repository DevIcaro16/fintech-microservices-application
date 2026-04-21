import { Elysia } from "elysia";

const WINDOW_MS = 60_000;
const MAX_REQUESTS = 100;

const windows = new Map<string, number[]>();

function isRateLimited(ip: string): boolean {
  const now = Date.now();
  const cutoff = now - WINDOW_MS;
  const timestamps = (windows.get(ip) ?? []).filter((t) => t > cutoff);
  timestamps.push(now);
  windows.set(ip, timestamps);
  return timestamps.length > MAX_REQUESTS;
}

setInterval(() => {
  const cutoff = Date.now() - WINDOW_MS;
  for (const [ip, ts] of windows) {
    if (ts[ts.length - 1] < cutoff) windows.delete(ip);
  }
}, WINDOW_MS);

export const rateLimiterPlugin = new Elysia({ name: "rate-limiter" }).onBeforeHandle(
  { as: "global" },
  ({ request, set }) => {
    const ip =
      request.headers.get("x-forwarded-for")?.split(",")[0].trim() ??
      "unknown";
    if (isRateLimited(ip)) {
      set.status = 429;
      return { error: "rate limit exceeded" };
    }
  }
);
