import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient } from "@aws-sdk/lib-dynamodb";

const raw = new DynamoDBClient({
  endpoint: process.env.DYNAMODB_ENDPOINT ?? "http://dynamodb-local.data.svc.cluster.local:8000",
  region: "us-east-1",
  credentials: { accessKeyId: "dummy", secretAccessKey: "dummy" },
});

export const dynamo = DynamoDBDocumentClient.from(raw);
