import { Elysia } from "elysia";
import { PUBLIC_ROUTES } from "../config/routes";

const AUTH_SERVICE =
  process.env.AUTH_SERVICE_URL ?? "http://auth-service.fintech.svc.cluster.local";

export const authGuardPlugin = new Elysia({ name: "auth-guard" })
  .derive({ as: "global" }, () => ({
    userId: undefined as string | undefined,
    userEmail: undefined as string | undefined,
  }))
  .onBeforeHandle({ as: "global" }, async ({ request, set, store }) => {
    const url = new URL(request.url);
    const routeKey = `${request.method} ${url.pathname}`;

    if (
      PUBLIC_ROUTES.has(routeKey) ||
      url.pathname === "/healthz" ||
      url.pathname === "/metrics"
    ) {
      return;
    }

    const authHeader = request.headers.get("authorization");
    if (!authHeader?.startsWith("Bearer ")) {
      set.status = 401;
      return { error: "missing authorization header" };
    }

    const res = await fetch(`${AUTH_SERVICE}/auth/validate`, {
      headers: {
        authorization: authHeader,
        "x-trace-id": request.headers.get("x-trace-id") ?? "",
      },
    }).catch(() => null);

    if (!res || res.status !== 200) {
      set.status = 401;
      return { error: "unauthorized" };
    }

    const payload = (await res.json()) as { user_id: string; email: string };
    // Attach user info to store for downstream use
    (store as Record<string, unknown>).userId = payload.user_id;
    (store as Record<string, unknown>).userEmail = payload.email;
  });
