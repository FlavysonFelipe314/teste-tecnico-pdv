import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import { call, Row, Item, RequestData, DocumentData, CacheMetrics } from "./api";
import "./style.css";
const documentLabels: Record<string, string> = {
  NOT_REQUESTED: "Ainda não solicitado", PENDING: "Aguardando emissão",
  PROCESSING: "Emitindo documento", RETRY_WAIT: "Aguardando nova tentativa",
  AUTHORIZED_SIMULATION: "Autorizado · XML disponível",
  REJECTED_SIMULATION: "Rejeitado pelo provedor", FAILED: "Falha · reprocessamento disponível",
};
function App() {
  const [token, setToken] = useState("gestor-local"),
    [store, setStore] = useState("loja-aurora");
  const [inventory, setInventory] = useState<Row[]>([]),
    [needs, setNeeds] = useState<Row[]>([]),
    [requests, setRequests] = useState<RequestData[]>([]);
  const [detail, setDetail] = useState<RequestData | null>(null),
    [doc, setDoc] = useState<DocumentData | null>(null),
    [received, setReceived] = useState<Record<string, number>>({});
  const [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [mode, setMode] = useState("sucesso");
  const [cacheMetrics, setCacheMetrics] = useState<CacheMetrics | null>(null);
  async function refresh() {
    const [a, b, c] = await Promise.all([
      call<Row[]>("/inventory", token),
      call<Row[]>("/needs?store=" + store, token),
      call<RequestData[]>("/requests", token),
    ]);
    setInventory(a);
    setNeeds(b);
    setRequests(c);
    if (detail) setDoc(await call<DocumentData>("/requests/" + detail.id + "/document", token));
  }
  useEffect(() => {
    let cancelled = false;
    Promise.all([
      call<Row[]>("/inventory", token),
      call<Row[]>("/needs?store=" + store, token),
      call<RequestData[]>("/requests", token),
    ])
      .then(([a, b, c]) => {
        if (!cancelled) {
          setInventory(a);
          setNeeds(b);
          setRequests(c);
        }
      })
      .catch((e) => {
        if (!cancelled) setMessage(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [token, store]);
  useEffect(() => {
    if (!detail || !doc || !["PENDING", "PROCESSING", "RETRY_WAIT"].includes(doc.status)) return;
    let cancelled = false;
    const id = detail.id;
    const timer = window.setInterval(() => {
      call<DocumentData>("/requests/" + id + "/document", token)
        .then(d => { if (!cancelled) setDoc(d); })
        .catch(() => { /* The refresh button remains available after a network interruption. */ });
    }, 2000);
    return () => { cancelled = true; window.clearInterval(timer); };
  }, [detail?.id, doc?.status, token]);
  async function run(action: () => Promise<void>) {
    setBusy(true);
    setMessage("");
    try {
      await action();
    } catch (e) {
      setMessage(e instanceof Error ? e.message : "Erro");
    } finally {
      setBusy(false);
    }
  }
  async function open(id: string) {
    const [r, d] = await Promise.all([
      call<RequestData>("/requests/" + id, token),
      call<DocumentData>("/requests/" + id + "/document", token),
    ]);
    setDetail(r);
    setDoc(d);
    setReceived(
      Object.fromEntries(
        r.items.map((i) => [i.product_id, i.received ?? i.requested]),
      ),
    );
  }
  async function create() {
    const r = await call<RequestData>("/requests", token, "POST", {
      destination: store,
      items: needs.map((i) => ({
        product_id: i.product_id,
        requested: i.need,
      })),
    });
    await refresh();
    await open(r.id);
    setMessage("Solicitação criada.");
  }
  async function confirm() {
    if (!detail) return;
    await call("/requests/" + detail.id + "/confirm", token, "POST", {
      items: detail.items.map((i) => ({
        product_id: i.product_id,
        received: received[i.product_id],
      })),
    });
    await refresh();
    await open(detail.id);
    setMessage("Recebimento confirmado. O documento será processado separadamente.");
  }
  async function download() {
    if (!detail) return;
    const r = await fetch("/admin/requests/" + detail.id + "/document/xml", {
      headers: { Authorization: "Bearer " + token },
    });
    if (!r.ok || r.headers.get("content-type")?.includes("json"))
      throw new Error("XML indisponível");
    const url = URL.createObjectURL(await r.blob());
    const a = document.createElement("a");
    a.href = url;
    a.download = detail.id + ".xml";
    a.click();
    URL.revokeObjectURL(url);
  }
  return (
    <>
      <header>
        <div className="brand">RC</div>
        <div>
          <strong>Rota Clara</strong>
          <small>Rede Brisa 27 · laboratório local</small>
        </div>
        <label className="profile">
          Perfil
          <select value={token} onChange={(e) => setToken(e.target.value)}>
            <option value="gestor-local">Gestor</option>
            <option value="operador-local">Operador</option>
          </select>
        </label>
      </header>
      <main>
        <div className="heading">
          <div>
            <span className="eyebrow">OPERAÇÃO DE LOJAS</span>
            <h1>Reposição e transferências</h1>
            <p>
              Consulte o estoque, solicite mercadorias e confira o recebido.
            </p>
          </div>
          <button onClick={() => run(refresh)} disabled={busy}>
            Atualizar
          </button>
        </div>
        {message && (
          <div role="status" className="message">
            {message}
          </div>
        )}
        <section>
          <div className="section-head">
            <h2>Necessidade de reposição</h2>
            <label>
              Loja
              <select value={store} onChange={(e) => setStore(e.target.value)}>
                <option value="loja-aurora">Loja Aurora</option>
                <option value="loja-lagoa">Loja Lagoa</option>
              </select>
            </label>
          </div>
          <table>
            <thead>
              <tr>
                <th>Produto</th>
                <th>Saldo</th>
                <th>Meta</th>
                <th>Necessidade</th>
              </tr>
            </thead>
            <tbody>
              {needs.map((i) => (
                <tr key={i.product_id}>
                  <td>{i.product_name}</td>
                  <td>{i.stock}</td>
                  <td>{i.target}</td>
                  <td>
                    <strong>{i.need}</strong>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {needs.length === 0 && <p>Nenhum item para repor.</p>}
          <button
            className="primary"
            disabled={busy || !needs.length || token !== "gestor-local"}
            onClick={() => run(create)}
          >
            Criar solicitação com estes itens
          </button>
        </section>
        <div className="grid">
          <section>
            <h2>Solicitações</h2>
            {requests.length === 0 ? (
              <p>Ainda não há solicitações.</p>
            ) : (
              requests.map((r) => (
                <button
                  className="request-row"
                  key={r.id}
                  onClick={() => run(() => open(r.id))}
                >
                  <span>
                    <strong>
                      {r.destination === "loja-aurora"
                        ? "Loja Aurora"
                        : "Loja Lagoa"}
                    </strong>
                    <small>{r.id.slice(0, 8)}</small>
                  </span>
                  <span className="badge">{r.status}</span>
                </button>
              ))
            )}
          </section>
          <section>
            <h2>Conferência da entrega</h2>
            {!detail ? (
              <p>Selecione uma solicitação ao lado.</p>
            ) : (
              <>
                <p className="mono">{detail.id}</p>
                <table>
                  <thead>
                    <tr>
                      <th>Produto</th>
                      <th>Solicitado</th>
                      <th>Recebido</th>
                      <th>Falta</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.items.map((i: Item) => (
                      <tr key={i.product_id}>
                        <td>{i.product_name}</td>
                        <td>{i.requested}</td>
                        <td>
                          <input
                            aria-label={"Recebido " + i.product_name}
                            type="number"
                            min="0"
                            max={i.requested}
                            disabled={busy || token !== "operador-local" || detail.status !== "OPEN"}
                            value={received[i.product_id] ?? 0}
                            onChange={(e) =>
                              setReceived({
                                ...received,
                                [i.product_id]: Number(e.target.value),
                              })
                            }
                          />
                        </td>
                        <td>{i.missing}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <button
                  className="primary"
                  disabled={busy || token !== "operador-local" || detail.status !== "OPEN"}
                  onClick={() => run(confirm)}
                >
                  {detail.status === "CONFIRMED" ? "Entrega confirmada" : "Confirmar entrega"}
                </button>
                <p>
                  Estado: <strong>{detail.status}</strong> · autor:{" "}
                  {detail.confirmed_by ?? "—"}
                </p>
                <div className="document">
                  <h3>Documento da transferência</h3>
                  <p>{doc ? documentLabels[doc.status] ?? doc.status : "—"}</p>
                  {doc?.protocol && <p>Protocolo do provedor: {doc.protocol}</p>}
                  {doc?.reason && <p>{doc.reason}</p>}
                  <small>Tentativas: {doc?.attempts ?? 0}</small>
                  {doc?.next_attempt_at && <p>Próxima tentativa: {new Date(doc.next_attempt_at).toLocaleString("pt-BR")}</p>}
                  {doc && ["FAILED", "RETRY_WAIT"].includes(doc.status) && token === "gestor-local" && (
                    <button disabled={busy} onClick={() => run(async () => {
                      await call("/requests/" + detail.id + "/document/reprocess", token, "POST");
                      await open(detail.id);
                      setMessage("Reprocessamento documental agendado.");
                    })}>Tentar emissão novamente</button>
                  )}
                  <button
                    disabled={busy || doc?.status !== "AUTHORIZED_SIMULATION"}
                    onClick={() => run(download)}
                  >
                    Baixar XML demonstrativo
                  </button>
                </div>
              </>
            )}
          </section>
        </div>
        <section>
          <h2>Estoque por local</h2>
          <table>
            <thead>
              <tr>
                <th>Local</th>
                <th>Produto</th>
                <th>Saldo</th>
                <th>Meta</th>
              </tr>
            </thead>
            <tbody>
              {inventory.map((i) => (
                <tr key={i.location_id + i.product_id}>
                  <td>{i.location_name}</td>
                  <td>{i.product_name}</td>
                  <td>{i.stock}</td>
                  <td>{i.target ?? "—"}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
        <section>
          <h2>Controles do laboratório</h2>
          <p>
            O provedor documental é fictício. Nenhum arquivo tem validade
            fiscal.
          </p>
          <label>
            Cenário fiscal
            <select value={mode} onChange={(e) => setMode(e.target.value)}>
              <option value="sucesso">Sucesso</option>
              <option value="rejeicao">Rejeição</option>
              <option value="indisponivel">Indisponível</option>
              <option value="resposta-perdida">Resposta perdida</option>
            </select>
          </label>
          <button
            disabled={busy || token !== "gestor-local"}
            onClick={() =>
              run(async () => {
                await call("/lab/fiscal-mode", token, "PUT", { mode });
                setMessage("Cenário fiscal configurado.");
              })
            }
          >
            Aplicar cenário
          </button>
          {token === "gestor-local" && <div className="document">
            <h3>Diagnóstico do cache de necessidades</h3>
            <button disabled={busy} onClick={() => run(async () => {
              setCacheMetrics(await call<CacheMetrics>("/lab/cache-metrics", token));
            })}>Consultar contadores</button>
            {cacheMetrics && <>
              <p>Cache: {cacheMetrics.cache_enabled ? "ativo" : "desativado"} · acertos: {cacheMetrics.cache_hits}
                {" · "}faltas: {cacheMetrics.cache_misses}{" · "}falhas: {cacheMetrics.cache_errors}</p>
              <p>Consultas de necessidades no banco: {cacheMetrics.needs_db_queries}
                {" · "}leituras de versão: {cacheMetrics.version_db_queries}</p>
              <button disabled={busy} onClick={() => run(async () => {
                setCacheMetrics(await call<CacheMetrics>("/lab/cache-enabled", token, "PUT",
                  { enabled: !cacheMetrics.cache_enabled }));
              })}>{cacheMetrics.cache_enabled ? "Desativar cache para comparação" : "Ativar cache"}</button>
            </>}
          </div>}
        </section>
      </main>
      <footer>Ambiente sintético · Rota Clara 1.0</footer>
    </>
  );
}
createRoot(document.getElementById("root")!).render(<App />);
