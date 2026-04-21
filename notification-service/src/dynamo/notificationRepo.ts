import { PutCommand } from "@aws-sdk/lib-dynamodb";
import { dynamo } from "./client";
import type { NotificationRecord } from "../domain/types";

const TABLE = "notification-log";

export async function saveNotification(record: NotificationRecord): Promise<void> {
  await dynamo.send(
    new PutCommand({
      TableName: TABLE,
      Item: {
        user_id:       record.user_id,
        created_at:    record.created_at,
        transfer_id:   record.transfer_id,
        status:        record.status,
        amount:        record.amount,
        counterpart_id: record.counterpart_id,
      },
    })
  );
}
