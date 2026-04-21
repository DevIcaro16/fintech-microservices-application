import { broadcast } from "../websocket/manager";
import { saveNotification } from "../dynamo/notificationRepo";
import type { TransferEvent, NotificationRecord, WSPayload } from "../domain/types";

export async function handleTransferCompleted(event: TransferEvent): Promise<void> {
  const timestamp = new Date().toISOString();

  const wsPayload = (counterpartId: string): WSPayload => ({
    transfer_id:   event.transfer_id,
    status:        event.status,
    amount:        event.amount,
    counterpart_id: counterpartId,
    timestamp,
  });

  const record = (userId: string, counterpartId: string): NotificationRecord => ({
    user_id:        userId,
    created_at:     timestamp,
    transfer_id:    event.transfer_id,
    status:         event.status,
    amount:         event.amount,
    counterpart_id: counterpartId,
  });

  // Notify and persist for both parties
  await Promise.all([
    saveNotification(record(event.source_account_id, event.destination_account_id)),
    saveNotification(record(event.destination_account_id, event.source_account_id)),
  ]);

  broadcast(event.transfer_id, wsPayload(event.destination_account_id));
  broadcast(event.transfer_id, wsPayload(event.source_account_id));
}

export async function handleTransferFailed(event: TransferEvent): Promise<void> {
  const timestamp = new Date().toISOString();

  await saveNotification({
    user_id:        event.source_account_id,
    created_at:     timestamp,
    transfer_id:    event.transfer_id,
    status:         event.status,
    amount:         event.amount,
    counterpart_id: event.destination_account_id,
  });

  broadcast(event.transfer_id, {
    transfer_id:   event.transfer_id,
    status:        event.status,
    amount:        event.amount,
    counterpart_id: event.destination_account_id,
    timestamp,
  });
}
