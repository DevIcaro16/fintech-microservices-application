import React from "react";
import type { WSPayload } from "../lib/ws";

type Props = {
  notifications: WSPayload[];
  onClose: () => void;
  onClear: () => void;
};

export default function NotificationPanel({ notifications, onClose, onClear }: Props) {
  return (
    <div style={{ position: "fixed", top: 0, right: 0, width: 320, height: "100vh", background: "#1e293b", borderLeft: "1px solid #334155", zIndex: 100, display: "flex", flexDirection: "column" }}>
      <div style={{ padding: "16px 20px", borderBottom: "1px solid #334155", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span style={{ fontWeight: "bold", color: "#e2e8f0" }}>Notificacoes</span>
        <div style={{ display: "flex", gap: 8 }}>
          {notifications.length > 0 && (
            <button onClick={onClear} style={{ background: "none", border: "none", color: "#64748b", cursor: "pointer", fontSize: 12 }}>Limpar</button>
          )}
          <button onClick={onClose} style={{ background: "none", border: "none", color: "#64748b", cursor: "pointer", fontSize: 18 }}>✕</button>
        </div>
      </div>
      <div style={{ flex: 1, overflowY: "auto", padding: 16 }}>
        {notifications.length === 0 ? (
          <p style={{ color: "#64748b", fontSize: 14, textAlign: "center", marginTop: 40 }}>Nenhuma notificacao</p>
        ) : (
          notifications.map((n, i) => (
            <div key={i} style={{ background: "#0f172a", borderRadius: 8, padding: 12, marginBottom: 8, borderLeft: `3px solid ${n.status === "COMPLETED" ? "#34d399" : "#f87171"}` }}>
              <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 4 }}>
                <span style={{ color: n.status === "COMPLETED" ? "#34d399" : "#f87171", fontSize: 12, fontWeight: "bold" }}>
                  {n.status === "COMPLETED" ? "✓ Concluida" : "✗ Falhou"}
                </span>
                <span style={{ color: "#64748b", fontSize: 11 }}>{new Date(n.timestamp).toLocaleTimeString("pt-BR")}</span>
              </div>
              <div style={{ color: "#e2e8f0", fontSize: 14 }}>R$ {n.amount}</div>
              <div style={{ color: "#64748b", fontSize: 11, marginTop: 2 }}>ID: {n.transfer_id.slice(0, 8)}...</div>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
