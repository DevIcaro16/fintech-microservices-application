package com.fintech.transfer.adapter.out.dynamodb;

import com.fintech.transfer.domain.NotificationEntry;
import com.fintech.transfer.port.out.NotificationPort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.Map;

@Component
public class DynamoDbNotificationAdapter implements NotificationPort {

    private static final String TABLE = "notification-log";
    private static final String GSI   = "status-next_retry_at-index";
    private final DynamoDbAsyncClient client;

    public DynamoDbNotificationAdapter(DynamoDbAsyncClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> save(NotificationEntry e) {
        PutItemRequest req = PutItemRequest.builder()
            .tableName(TABLE)
            .item(Map.of(
                "transfer_id",  AttributeValue.fromS(e.getTransferId()),
                "created_at",   AttributeValue.fromS(e.getCreatedAt()),
                "callback_url", AttributeValue.fromS(e.getCallbackUrl()),
                "payload",      AttributeValue.fromS(e.getPayload()),
                "attempts",     AttributeValue.fromN(String.valueOf(e.getAttempts())),
                "next_retry_at",AttributeValue.fromS(e.getNextRetryAt()),
                "status",       AttributeValue.fromS(e.getStatus())
            ))
            .build();
        return Mono.fromCompletionStage(() -> client.putItem(req)).then();
    }

    @Override
    public Flux<NotificationEntry> findPending(Instant now, int limit) {
        QueryRequest req = QueryRequest.builder()
            .tableName(TABLE)
            .indexName(GSI)
            .keyConditionExpression("#s = :pending AND next_retry_at <= :now")
            .expressionAttributeNames(Map.of("#s", "status"))
            .expressionAttributeValues(Map.of(
                ":pending", AttributeValue.fromS("PENDING"),
                ":now",     AttributeValue.fromS(now.toString())
            ))
            .limit(limit)
            .build();
        return Mono.fromCompletionStage(() -> client.query(req))
            .flatMapMany(r -> Flux.fromIterable(r.items()))
            .map(this::mapEntry);
    }

    @Override
    public Mono<Void> markDelivered(String transferId, String createdAt) {
        return updateStatus(transferId, createdAt, "DELIVERED");
    }

    @Override
    public Mono<Void> markExhausted(String transferId, String createdAt) {
        return updateStatus(transferId, createdAt, "EXHAUSTED");
    }

    @Override
    public Mono<Void> updateAttempt(NotificationEntry e) {
        UpdateItemRequest req = UpdateItemRequest.builder()
            .tableName(TABLE)
            .key(Map.of(
                "transfer_id", AttributeValue.fromS(e.getTransferId()),
                "created_at",  AttributeValue.fromS(e.getCreatedAt())
            ))
            .updateExpression("SET attempts = :a, next_retry_at = :nra, #s = :s")
            .expressionAttributeNames(Map.of("#s", "status"))
            .expressionAttributeValues(Map.of(
                ":a",   AttributeValue.fromN(String.valueOf(e.getAttempts())),
                ":nra", AttributeValue.fromS(e.getNextRetryAt()),
                ":s",   AttributeValue.fromS(e.getStatus())
            ))
            .build();
        return Mono.fromCompletionStage(() -> client.updateItem(req)).then();
    }

    private Mono<Void> updateStatus(String transferId, String createdAt, String newStatus) {
        UpdateItemRequest req = UpdateItemRequest.builder()
            .tableName(TABLE)
            .key(Map.of(
                "transfer_id", AttributeValue.fromS(transferId),
                "created_at",  AttributeValue.fromS(createdAt)
            ))
            .updateExpression("SET #s = :s")
            .expressionAttributeNames(Map.of("#s", "status"))
            .expressionAttributeValues(Map.of(":s", AttributeValue.fromS(newStatus)))
            .build();
        return Mono.fromCompletionStage(() -> client.updateItem(req)).then();
    }

    private NotificationEntry mapEntry(Map<String, AttributeValue> item) {
        return new NotificationEntry(
            item.get("transfer_id").s(),
            item.get("created_at").s(),
            item.get("callback_url").s(),
            item.get("payload").s(),
            Integer.parseInt(item.get("attempts").n()),
            item.get("next_retry_at").s(),
            item.get("status").s()
        );
    }
}
