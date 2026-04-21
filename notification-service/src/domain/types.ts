export type TransferEvent = {
  transfer_id: string;
  status: "COMPLETED" | "FAILED";
  source_account_id: string;
  destination_account_id: string;
  amount: string;
};

export type NotificationRecord = {
  user_id: string;
  created_at: string;
  transfer_id: string;
  status: string;
  amount: string;
  counterpart_id: string;
};

export type WSPayload = {
  transfer_id: string;
  status: string;
  amount: string;
  counterpart_id: string;
  timestamp: string;
};
