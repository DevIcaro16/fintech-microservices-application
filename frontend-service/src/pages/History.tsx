import Layout from "../components/Layout";
import TransferCard, { type Transfer } from "../components/TransferCard";

type Props = { transfers: Transfer[]; publicApiUrl: string };

export default function History({ transfers, publicApiUrl }: Props) {
  return (
    <>
      <script dangerouslySetInnerHTML={{ __html: `window.__CONFIG__={apiUrl:${JSON.stringify(publicApiUrl)}}` }} />
      <Layout>
        <div style={{ maxWidth: 640, margin: "0 auto" }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 20 }}>
            <h2 style={{ color: "#e2e8f0", margin: 0 }}>Histórico</h2>
            <a href="/transfers/new" style={{ background: "#0ea5e9", color: "white", padding: "8px 16px", borderRadius: 6, textDecoration: "none", fontSize: 13, fontWeight: "bold" }}>+ Nova</a>
          </div>
          {transfers.length === 0 ? (
            <p style={{ color: "#64748b", textAlign: "center", marginTop: 60 }}>Nenhuma transferência encontrada.</p>
          ) : (
            transfers.map(t => <TransferCard key={t.transfer_id} transfer={t} />)
          )}
        </div>
      </Layout>
      <script src="/client.js" defer />
    </>
  );
}
