export type WSPayload = {
  transfer_id: string;
  status: string;
  amount: string;
  counterpart_id: string;
  timestamp: string;
};

type Handler = (payload: WSPayload) => void;

export function connectWS(transferId: string, onMessage: Handler): () => void {
  const apiUrl = (window as any).__CONFIG__?.apiUrl ?? "http://127.0.0.1";
  const wsBase = apiUrl.replace(/^http/, "ws");
  let socket: WebSocket | null = null;
  let retryCount = 0;
  let stopped = false;

  function connect() {
    if (stopped) return;
    socket = new WebSocket(`${wsBase}/ws?transfer_id=${transferId}`);

    socket.onmessage = (e) => {
      try {
        onMessage(JSON.parse(e.data) as WSPayload);
      } catch {}
    };

    socket.onclose = () => {
      if (stopped) return;
      const delay = Math.min(1000 * Math.pow(2, retryCount), 30000);
      retryCount++;
      setTimeout(connect, delay);
    };

    socket.onerror = () => socket?.close();
  }

  connect();

  return () => {
    stopped = true;
    socket?.close();
  };
}
