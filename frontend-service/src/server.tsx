import { Elysia } from "elysia";
import { renderToString } from "react-dom/server";
import { apiFetch } from "./lib/api";
import Login from "./pages/Login";
import Dashboard from "./pages/Dashboard";
import NewTransfer from "./pages/NewTransfer";
import History from "./pages/History";
import type { Transfer } from "./components/TransferCard";

const PUBLIC_API_URL = process.env.PUBLIC_API_GATEWAY_URL ?? "http://127.0.0.1";

function html(body: string): Response {
  return new Response(
    `<!DOCTYPE html><html lang="pt-BR"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>FintechApp</title></head><body style="margin:0">${body}</body></html>`,
    { headers: { "Content-Type": "text/html;charset=utf-8" } }
  );
}

function getToken(cookieHeader: string | null): string | null {
  if (!cookieHeader) return null;
  const match = cookieHeader.match(/(?:^|;\s*)token=([^;]+)/);
  return match ? match[1] : null;
}

const app = new Elysia()
  .get("/client.js", () => Bun.file("src/public/client.js"))

  .get("/login", ({ request }) => {
    const token = getToken(request.headers.get("cookie"));
    if (token) return Response.redirect("/", 302);
    return html(renderToString(<Login />));
  })

  .post("/auth/login", async ({ request }) => {
    const form = await request.formData();
    const email = form.get("email") as string;
    const password = form.get("password") as string;
    const res = await apiFetch("/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password }),
    });
    if (!res.ok) return html(renderToString(<Login error="Email ou senha inválidos" />));
    const { access_token } = await res.json() as { access_token: string };
    return new Response(null, {
      status: 302,
      headers: {
        Location: "/",
        "Set-Cookie": `token=${access_token}; HttpOnly; Path=/; SameSite=Strict`,
      },
    });
  })

  .post("/logout", () =>
    new Response(null, {
      status: 302,
      headers: {
        Location: "/login",
        "Set-Cookie": "token=; HttpOnly; Path=/; Max-Age=0",
      },
    })
  )

  .get("/", async ({ request }) => {
    const token = getToken(request.headers.get("cookie"));
    if (!token) return Response.redirect("/login", 302);

    const userRes = await apiFetch("/auth/validate", {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!userRes.ok) return Response.redirect("/login", 302);
    const { user_id } = await userRes.json() as { user_id: string };

    const [balanceRes, transfersRes] = await Promise.all([
      apiFetch(`/accounts/${user_id}/balance`, { headers: { Authorization: `Bearer ${token}` } }),
      apiFetch(`/transfers?user_id=${user_id}`, { headers: { Authorization: `Bearer ${token}` } }),
    ]);

    const { balance } = balanceRes.ok
      ? await balanceRes.json() as { balance: string }
      : { balance: "0.00" };

    const transfers: Transfer[] = transfersRes.ok
      ? (await transfersRes.json() as Transfer[]).slice(0, 3)
      : [];

    return html(renderToString(
      <Dashboard balance={balance} accountId={user_id} recentTransfers={transfers} publicApiUrl={PUBLIC_API_URL} />
    ));
  })

  .get("/transfers", async ({ request }) => {
    const token = getToken(request.headers.get("cookie"));
    if (!token) return Response.redirect("/login", 302);

    const userRes = await apiFetch("/auth/validate", {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (!userRes.ok) return Response.redirect("/login", 302);
    const { user_id } = await userRes.json() as { user_id: string };

    const res = await apiFetch(`/transfers?user_id=${user_id}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const transfers: Transfer[] = res.ok ? await res.json() as Transfer[] : [];

    return html(renderToString(<History transfers={transfers} publicApiUrl={PUBLIC_API_URL} />));
  })

  .get("/transfers/new", ({ request }) => {
    const token = getToken(request.headers.get("cookie"));
    if (!token) return Response.redirect("/login", 302);
    return html(renderToString(<NewTransfer publicApiUrl={PUBLIC_API_URL} />));
  })

  .post("/transfers", async ({ request }) => {
    const token = getToken(request.headers.get("cookie"));
    if (!token) return Response.redirect("/login", 302);

    const form = await request.formData();
    const source_account_id = form.get("source_account_id") as string;
    const destination_account_id = form.get("destination_account_id") as string;
    const amount = form.get("amount") as string;

    const res = await apiFetch("/transfers", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: JSON.stringify({ source_account_id, destination_account_id, amount }),
    });

    if (!res.ok) {
      return html(renderToString(
        <NewTransfer error="Falha ao criar transferência. Verifique os dados e tente novamente." publicApiUrl={PUBLIC_API_URL} />
      ));
    }

    const { transfer_id } = await res.json() as { transfer_id: string };
    return new Response(null, {
      status: 302,
      headers: {
        Location: "/transfers",
        "Set-Cookie": `active_transfer_id=${transfer_id}; Path=/; SameSite=Strict; Max-Age=300`,
      },
    });
  })

  .listen(Number(process.env.PORT ?? 3002));

console.log(JSON.stringify({
  level: "info",
  service: "frontend-service",
  msg: `listening on :${app.server?.port}`,
  ts: new Date().toISOString(),
}));
