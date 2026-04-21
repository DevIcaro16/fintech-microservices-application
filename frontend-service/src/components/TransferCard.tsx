export type Transfer = {
  transfer_id: string;
  status: "PENDING" | "COMPLETED" | "FAILED";
  source_account_id: string;
  destination_account_id: string;
  amount: string;
  created_at: string;
};

const STATUS_COLOR: Record<string, string> = {
  COMPLETED: "#34d399",
  FAILED: "#f87171",
  PENDING: "#fbbf24",
};

const STATUS_LABEL: Record<string, string> = {
  COMPLETED: "✓ Concluida",
  FAILED: "✗ Falhou",
  PENDING: "→ Pendente",
};

export default function TransferCard({ transfer }: { transfer: Transfer }) {
  const color = STATUS_COLOR[transfer.status] ?? "#64748b";
  return (
    <div style={{ background: "#1e293b", borderRadius: 8, padding: 16, borderLeft: `3px solid ${color}`, marginBottom: 8 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <span style={{ color, fontSize: 13, fontWeight: "bold" }}>{STATUS_LABEL[transfer.status] ?? transfer.status}</span>
        <span style={{ color: "#e2e8f0", fontSize: 16, fontWeight: "bold" }}>R$ {transfer.amount}</span>
      </div>
      <div style={{ marginTop: 6, fontSize: 12, color: "#64748b" }}>
        <div>De: {transfer.source_account_id.slice(0, 12)}...</div>
        <div>Para: {transfer.destination_account_id.slice(0, 12)}...</div>
        <div style={{ marginTop: 4 }}>{new Date(transfer.created_at).toLocaleDateString("pt-BR")}</div>
      </div>
    </div>
  );
}
