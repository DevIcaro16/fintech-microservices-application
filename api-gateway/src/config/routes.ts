const AUTH_SERVICE =
  process.env.AUTH_SERVICE_URL ?? "http://auth-service.fintech.svc.cluster.local";
const ACCOUNT_SERVICE =
  process.env.ACCOUNT_SERVICE_URL ?? "http://account-service.fintech.svc.cluster.local";
const TRANSFER_SERVICE =
  process.env.TRANSFER_SERVICE_URL ?? "http://transfer-service.fintech.svc.cluster.local";
const NOTIFICATION_SERVICE =
  process.env.NOTIFICATION_SERVICE_URL ?? "http://notification-service.fintech.svc.cluster.local";

export const PUBLIC_ROUTES = new Set([
  "POST /auth/login",
  "POST /auth/refresh",
  "GET /ws",
]);

export const UPSTREAM_MAP: Record<string, string> = {
  "/auth": AUTH_SERVICE,
  "/accounts": ACCOUNT_SERVICE,
  "/transfers": TRANSFER_SERVICE,
  "/ws": NOTIFICATION_SERVICE,
};

export function resolveUpstream(path: string): string | null {
  for (const [prefix, url] of Object.entries(UPSTREAM_MAP)) {
    if (path.startsWith(prefix)) return url;
  }
  return null;
}
