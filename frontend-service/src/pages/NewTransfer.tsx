import Layout from "../components/Layout";

type Props = { error?: string; publicApiUrl: string };

export default function NewTransfer({ error, publicApiUrl }: Props) {
  return (
    <>
      <script dangerouslySetInnerHTML={{ __html: `window.__CONFIG__={apiUrl:${JSON.stringify(publicApiUrl)}}` }} />
      <Layout>
        <div style={{ maxWidth: 480, margin: "0 auto" }}>
          <h2 style={{ color: "#e2e8f0", marginBottom: 24 }}>Nova Transferência</h2>
          {error && (
            <div style={{ background: "#450a0a", border: "1px solid #f87171", borderRadius: 6, padding: 10, marginBottom: 16, color: "#f87171", fontSize: 13 }}>
              {error}
            </div>
          )}
          <form id="transfer-form" method="POST" action="/transfers">
            <div style={{ background: "#1e293b", borderRadius: 12, padding: 24, display: "flex", flexDirection: "column", gap: 16 }}>
              <div>
                <label style={{ display: "block", color: "#94a3b8", fontSize: 13, marginBottom: 6 }}>Conta de origem (ID)</label>
                <input name="source_account_id" required placeholder="uuid da conta origem"
                  style={{ width: "100%", background: "#0f172a", border: "1px solid #334155", borderRadius: 6, padding: "10px 12px", color: "#e2e8f0", fontSize: 14, boxSizing: "border-box" }} />
              </div>
              <div>
                <label style={{ display: "block", color: "#94a3b8", fontSize: 13, marginBottom: 6 }}>Conta de destino (ID)</label>
                <input name="destination_account_id" required placeholder="uuid da conta destino"
                  style={{ width: "100%", background: "#0f172a", border: "1px solid #334155", borderRadius: 6, padding: "10px 12px", color: "#e2e8f0", fontSize: 14, boxSizing: "border-box" }} />
              </div>
              <div>
                <label style={{ display: "block", color: "#94a3b8", fontSize: 13, marginBottom: 6 }}>Valor (R$)</label>
                <input name="amount" type="number" step="0.01" min="0.01" required placeholder="0.00"
                  style={{ width: "100%", background: "#0f172a", border: "1px solid #334155", borderRadius: 6, padding: "10px 12px", color: "#e2e8f0", fontSize: 14, boxSizing: "border-box" }} />
              </div>
              <button type="submit" style={{ background: "#0ea5e9", color: "white", border: "none", borderRadius: 6, padding: 14, fontSize: 15, fontWeight: "bold", cursor: "pointer", marginTop: 8 }}>
                Enviar Transferência
              </button>
            </div>
          </form>
        </div>
      </Layout>
      <script src="/client.js" defer />
    </>
  );
}
