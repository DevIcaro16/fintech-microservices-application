import React from "react";

type Props = { error?: string };

export default function Login({ error }: Props) {
  return (
    <div style={{ minHeight: "100vh", background: "#0f172a", display: "flex", alignItems: "center", justifyContent: "center", fontFamily: "system-ui, sans-serif" }}>
      <div style={{ background: "#1e293b", borderRadius: 12, padding: 40, width: 360 }}>
        <h1 style={{ color: "#38bdf8", fontSize: 22, margin: "0 0 8px", textAlign: "center" }}>💳 FintechApp</h1>
        <p style={{ color: "#64748b", textAlign: "center", fontSize: 14, margin: "0 0 28px" }}>Entre na sua conta</p>
        {error && (
          <div style={{ background: "#450a0a", border: "1px solid #f87171", borderRadius: 6, padding: 10, marginBottom: 16, color: "#f87171", fontSize: 13 }}>
            {error}
          </div>
        )}
        <form method="POST" action="/auth/login">
          <div style={{ marginBottom: 16 }}>
            <label style={{ display: "block", color: "#94a3b8", fontSize: 13, marginBottom: 6 }}>Email</label>
            <input name="email" type="email" required placeholder="seu@email.com"
              style={{ width: "100%", background: "#0f172a", border: "1px solid #334155", borderRadius: 6, padding: "10px 12px", color: "#e2e8f0", fontSize: 14, boxSizing: "border-box" }} />
          </div>
          <div style={{ marginBottom: 24 }}>
            <label style={{ display: "block", color: "#94a3b8", fontSize: 13, marginBottom: 6 }}>Senha</label>
            <input name="password" type="password" required placeholder="••••••••"
              style={{ width: "100%", background: "#0f172a", border: "1px solid #334155", borderRadius: 6, padding: "10px 12px", color: "#e2e8f0", fontSize: 14, boxSizing: "border-box" }} />
          </div>
          <button type="submit" style={{ width: "100%", background: "#0ea5e9", color: "white", border: "none", borderRadius: 6, padding: 12, fontSize: 15, fontWeight: "bold", cursor: "pointer" }}>
            Entrar
          </button>
        </form>
      </div>
    </div>
  );
}
