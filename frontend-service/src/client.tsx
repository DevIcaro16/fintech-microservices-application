import { useState, useEffect } from "react";
import { createRoot } from "react-dom/client";
import { connectWS, type WSPayload } from "./lib/ws";
import NotificationPanel from "./components/NotificationPanel";

function NotificationManager() {
  const [notifications, setNotifications] = useState<WSPayload[]>([]);
  const [panelOpen, setPanelOpen] = useState(false);

  useEffect(() => {
    const match = document.cookie.match(/(?:^|;\s*)active_transfer_id=([^;]+)/);
    const transferId = match ? match[1] : null;
    if (!transferId) return;
    const disconnect = connectWS(transferId, (payload) => {
      setNotifications((prev) => [payload, ...prev]);
    });
    return disconnect;
  }, []);

  useEffect(() => {
    const badge = document.getElementById("notification-badge");
    if (!badge) return;
    if (notifications.length > 0) {
      badge.textContent = notifications.length > 9 ? "9+" : String(notifications.length);
      badge.style.display = "flex";
    } else {
      badge.style.display = "none";
    }
  }, [notifications]);

  useEffect(() => {
    const bell = document.getElementById("notification-bell");
    if (!bell) return;
    const handler = () => setPanelOpen((p) => !p);
    bell.addEventListener("click", handler);
    return () => bell.removeEventListener("click", handler);
  }, []);

  if (!panelOpen) return null;

  return (
    <NotificationPanel
      notifications={notifications}
      onClose={() => setPanelOpen(false)}
      onClear={() => setNotifications([])}
    />
  );
}

const root = document.getElementById("notification-root");
if (root) {
  createRoot(root).render(<NotificationManager />);
}
