import { describe, it, expect, mock, beforeEach } from "bun:test";
import type { TransferEvent } from "../domain/types";

// Mocks
const broadcastMock = mock(() => { });
const saveMock = mock(async () => { });

mock.module("../websocket/manager", () => ({ broadcast: broadcastMock }));
mock.module("../dynamo/notificationRepo", () => ({ saveNotification: saveMock }));

const { handleTransferCompleted, handleTransferFailed } = await import("../kafka/handlers");

const completedEvent: TransferEvent = {
  transfer_id: "tx-1",
  status: "COMPLETED",
  source_account_id: "acc-src",
  destination_account_id: "acc-dst",
  amount: "100.00",
};

const failedEvent: TransferEvent = {
  transfer_id: "tx-2",
  status: "FAILED",
  source_account_id: "acc-src",
  destination_account_id: "acc-dst",
  amount: "50.00",
};

describe("Kafka handlers", () => {
  beforeEach(() => {
    broadcastMock.mockClear();
    saveMock.mockClear();
  });

  it("handleTransferCompleted broadcasts to source and destination", async () => {
    await handleTransferCompleted(completedEvent);
    expect(broadcastMock).toHaveBeenCalledTimes(2);
    expect(broadcastMock).toHaveBeenCalledWith("tx-1", expect.objectContaining({ status: "COMPLETED" }));
  });

  it("handleTransferCompleted saves two DynamoDB records", async () => {
    await handleTransferCompleted(completedEvent);
    expect(saveMock).toHaveBeenCalledTimes(2);
    const calls = saveMock.mock.calls;
    const userIds = calls.map((c: any[]) => c[0].user_id);
    expect(userIds).toContain("acc-src");
    expect(userIds).toContain("acc-dst");
  });

  it("handleTransferFailed broadcasts only to source", async () => {
    await handleTransferFailed(failedEvent);
    expect(broadcastMock).toHaveBeenCalledTimes(1);
    expect(broadcastMock).toHaveBeenCalledWith("tx-2", expect.objectContaining({ status: "FAILED" }));
  });

  it("handleTransferFailed saves one DynamoDB record for source", async () => {
    await handleTransferFailed(failedEvent);
    expect(saveMock).toHaveBeenCalledTimes(1);
    expect((saveMock.mock.calls as any[][])[0][0].user_id).toBe("acc-src");
  });
});
