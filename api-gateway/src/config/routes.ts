const AUTH_SERVICE =
  process.env.AUTH_SERVICE_URL ?? "http://auth-service.fintech.svc.cluster.local";

export const PUBLIC_ROUTES = new Set([
  "POST /auth/login",
  "POST /auth/refresh",
]);

export const UPSTREAM_MAP: Record<string, string> = {
  "/auth": AUTH_SERVICE,
};

export function resolveUpstream(path: string): string | null {
  for (const [prefix, url] of Object.entries(UPSTREAM_MAP)) {
    if (path.startsWith(prefix)) return url;
  }
  return null;
}
