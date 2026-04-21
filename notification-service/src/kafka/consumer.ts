import { Kafka, logLevel } from "kafkajs";
import { handleTransferCompleted, handleTransferFailed } from "./handlers";
import type { TransferEvent } from "../domain/types";

const kafka = new Kafka({
  clientId: "notification-service",
  brokers: (process.env.KAFKA_BOOTSTRAP ?? "localhost:9092").split(","),
  logLevel: logLevel.WARN,
});

const consumer = kafka.consumer({ groupId: "notification-service" });

export async function startConsumer(): Promise<void> {
  await consumer.connect();
  await consumer.subscribe({ topics: ["transfer-completed", "transfer-failed"], fromBeginning: false });

  await consumer.run({
    eachMessage: async ({ topic, message }) => {
      if (!message.value) return;
      let event: TransferEvent;
      try {
        event = JSON.parse(message.value.toString()) as TransferEvent;
      } catch {
        console.error(JSON.stringify({ level: "error", service: "notification-service", msg: "invalid kafka payload", topic }));
        return;
      }

      if (topic === "transfer-completed") {
        await handleTransferCompleted(event);
      } else if (topic === "transfer-failed") {
        await handleTransferFailed(event);
      }
    },
  });

  console.log(JSON.stringify({ level: "info", service: "notification-service", msg: "kafka consumer running" }));
}

export async function stopConsumer(): Promise<void> {
  await consumer.disconnect();
}
