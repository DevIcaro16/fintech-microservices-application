import Layout from "../components/Layout";
import TransferCard, { type Transfer } from "../components/TransferCard";

type Props = {
  balance: string;
  accountId: string;
  recentTransfers: Transfer[];
  publicApiUrl: string;
};

export default function Dashboard({ balance, accountId, recentTransfers, publicApiUrl }: Props) {
  return (
    <>
      <script dangerouslySetInnerHTML={{ __html: `window.__CONFIG__={apiUrl:${JSON.stringify(publicApiUrl)}}` }} />
      <Layout>
        <div style={{ maxWidth: 640, margin: "0 auto" }}>
          <div style={{ background: "#1e293b", borderRadius: 12, padding: 24, marginBottom: 16, borderLeft: "4px solid #0ea5e9" }}>
            <div style={{ color: "#64748b", fontSize: 13, marginBottom: 6 }}>Saldo disponível</div>
            <div style={{ color: "#e2e8f0", fontSize: 32, fontWeight: "bold" }}>R$ {balance}</div>
            <div style={{ color: "#64748b", fontSize: 12, marginTop: 6 }}>Conta: {accountId.slice(0, 12)}...</div>
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12, marginBottom: 24 }}>
            <a href="/transfers/new" style={{ background: "#0ea5e9", borderRadius: 8, padding: 16, textAlign: "center", textDecoration: "none", color: "white", fontWeight: "bold", fontSize: 14, display: "block" }}>
              + Nova Transferência
            </a>
            <a href="/transfers" style={{ background: "#1e293b", borderRadius: 8, padding: 16, textAlign: "center", textDecoration: "none", color: "#94a3b8", fontSize: 14, display: "block", border: "1px solid #334155" }}>
              Ver Histórico
            </a>
          </div>
          {recentTransfers.length > 0 && (
            <>
              <div style={{ color: "#64748b", fontSize: 13, marginBottom: 10 }}>Últimas transferências</div>
              {recentTransfers.map(t => <TransferCard key={t.transfer_id} transfer={t} />)}
            </>
          )}
        </div>
      </Layout>
      <script src="/client.js" defer />
    </>
  );
}
