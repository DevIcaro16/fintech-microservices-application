package com.fintech.transfer.adapter.out.dynamodb;

import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferHistorySummary;
import com.fintech.transfer.port.out.TransferHistoryPort;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.util.Map;

@Component
public class DynamoDbHistoryAdapter implements TransferHistoryPort {

    private static final String TABLE = "transfer-history";
    private final DynamoDbAsyncClient client;

    public DynamoDbHistoryAdapter(DynamoDbAsyncClient client) {
        this.client = client;
    }

    @Override
    public Mono<Void> save(Transfer t) {
        PutItemRequest req = PutItemRequest.builder()
            .tableName(TABLE)
            .item(Map.of(
                "user_id",                AttributeValue.fromS(t.getUserId()),
                "created_at",             AttributeValue.fromS(t.getCreatedAt().toString()),
                "transfer_id",            AttributeValue.fromS(t.getId()),
                "status",                 AttributeValue.fromS(t.getStatus().name()),
                "amount",                 AttributeValue.fromS(t.getAmount().toPlainString()),
                "destination_account_id", AttributeValue.fromS(t.getDestinationAccountId())
            ))
            .build();
        return Mono.fromCompletionStage(() -> client.putItem(req)).then();
    }

    @Override
    public Flux<TransferHistorySummary> findByUserId(String userId) {
        QueryRequest req = QueryRequest.builder()
            .tableName(TABLE)
            .keyConditionExpression("user_id = :uid")
            .expressionAttributeValues(Map.of(":uid", AttributeValue.fromS(userId)))
            .scanIndexForward(false)
            .build();
        return Mono.fromCompletionStage(() -> client.query(req))
            .flatMapMany(r -> Flux.fromIterable(r.items()))
            .map(item -> new TransferHistorySummary(
                item.get("transfer_id").s(),
                item.get("status").s(),
                item.get("amount").s(),
                item.get("created_at").s(),
                item.get("destination_account_id").s()
            ));
    }
}
