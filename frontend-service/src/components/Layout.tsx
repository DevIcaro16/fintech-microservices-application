import React from "react";

type Props = { children: React.ReactNode };

export default function Layout({ children }: Props) {
  return (
    <div style={{ minHeight: "100vh", background: "#0f172a", color: "#e2e8f0", fontFamily: "system-ui, sans-serif" }}>
      <header style={{ background: "#1e293b", padding: "12px 24px", display: "flex", alignItems: "center", justifyContent: "space-between", borderBottom: "1px solid #334155" }}>
        <span style={{ color: "#38bdf8", fontWeight: "bold", fontSize: 16 }}>💳 FintechApp</span>
        <nav style={{ display: "flex", gap: 16, alignItems: "center" }}>
          <a href="/" style={{ color: "#94a3b8", textDecoration: "none", fontSize: 14 }}>Dashboard</a>
          <a href="/transfers" style={{ color: "#94a3b8", textDecoration: "none", fontSize: 14 }}>Historico</a>
          <a href="/transfers/new" style={{ color: "#94a3b8", textDecoration: "none", fontSize: 14 }}>Transferir</a>
          <button id="notification-bell" style={{ position: "relative", background: "none", border: "none", cursor: "pointer", fontSize: 18, color: "#94a3b8" }}>
            🔔
            <span id="notification-badge" style={{ display: "none", position: "absolute", top: -4, right: -4, background: "#ef4444", color: "white", fontSize: 9, width: 14, height: 14, borderRadius: "50%", alignItems: "center", justifyContent: "center" }}>0</span>
          </button>
          <form method="POST" action="/logout" style={{ margin: 0 }}>
            <button type="submit" style={{ background: "none", border: "1px solid #475569", color: "#94a3b8", padding: "4px 10px", borderRadius: 4, cursor: "pointer", fontSize: 13 }}>Sair</button>
          </form>
        </nav>
      </header>
      <main style={{ padding: 24 }}>{children}</main>
      <div id="notification-root" />
    </div>
  );
}
