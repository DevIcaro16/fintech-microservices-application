package com.fintech.transfer.adapter.out.dynamodb;

import com.fintech.transfer.domain.NotificationEntry;
import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferHistorySummary;
import com.fintech.transfer.domain.TransferStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

@Testcontainers
class DynamoDbAdapterIT {

    @Container
    static GenericContainer<?> dynamodb = new GenericContainer<>("amazon/dynamodb-local:2.5.4")
        .withExposedPorts(8000)
        .withCommand("-jar DynamoDBLocal.jar -sharedDb -inMemory");

    static DynamoDbAsyncClient client;
    static DynamoDbHistoryAdapter historyAdapter;
    static DynamoDbNotificationAdapter notificationAdapter;

    @BeforeAll
    static void setup() throws Exception {
        client = DynamoDbAsyncClient.builder()
            .endpointOverride(URI.create("http://localhost:" + dynamodb.getMappedPort(8000)))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("dummy", "dummy")
            ))
            .httpClient(NettyNioAsyncHttpClient.builder().build())
            .build();

        client.createTable(CreateTableRequest.builder()
            .tableName("transfer-history")
            .attributeDefinitions(
                AttributeDefinition.builder().attributeName("user_id").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("created_at").attributeType(ScalarAttributeType.S).build()
            )
            .keySchema(
                KeySchemaElement.builder().attributeName("user_id").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("created_at").keyType(KeyType.RANGE).build()
            )
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .build()
        ).get();

        client.createTable(CreateTableRequest.builder()
            .tableName("notification-log")
            .attributeDefinitions(
                AttributeDefinition.builder().attributeName("transfer_id").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("created_at").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("status").attributeType(ScalarAttributeType.S).build(),
                AttributeDefinition.builder().attributeName("next_retry_at").attributeType(ScalarAttributeType.S).build()
            )
            .keySchema(
                KeySchemaElement.builder().attributeName("transfer_id").keyType(KeyType.HASH).build(),
                KeySchemaElement.builder().attributeName("created_at").keyType(KeyType.RANGE).build()
            )
            .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                .indexName("status-next_retry_at-index")
                .keySchema(
                    KeySchemaElement.builder().attributeName("status").keyType(KeyType.HASH).build(),
                    KeySchemaElement.builder().attributeName("next_retry_at").keyType(KeyType.RANGE).build()
                )
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                .build()
            )
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .build()
        ).get();

        historyAdapter = new DynamoDbHistoryAdapter(client);
        notificationAdapter = new DynamoDbNotificationAdapter(client);
    }

    @Test
    void history_saveAndQuery() {
        Transfer t = new Transfer("tx-it-1", "src-1", "dst-1",
            BigDecimal.valueOf(100), TransferStatus.COMPLETED, null, "src-1",
            Instant.now(), Instant.now());

        StepVerifier.create(historyAdapter.save(t).thenMany(historyAdapter.findByUserId("src-1")))
            .assertNext((TransferHistorySummary s) -> {
                assert s.getTransferId().equals("tx-it-1");
                assert s.getStatus().equals("COMPLETED");
            })
            .verifyComplete();
    }

    @Test
    void notification_saveAndFindPending() {
        NotificationEntry entry = NotificationEntry.pending("tx-it-2", "http://cb", "{\"status\":\"COMPLETED\"}");

        StepVerifier.create(
            notificationAdapter.save(entry)
                .thenMany(notificationAdapter.findPending(Instant.now().plusSeconds(1), 10))
        )
            .assertNext(n -> {
                assert n.getTransferId().equals("tx-it-2");
                assert n.getStatus().equals("PENDING");
            })
            .verifyComplete();
    }

    @Test
    void notification_markDelivered() {
        NotificationEntry entry = NotificationEntry.pending("tx-it-3", "http://cb", "{}");

        StepVerifier.create(
            notificationAdapter.save(entry)
                .then(notificationAdapter.markDelivered(entry.getTransferId(), entry.getCreatedAt()))
                .thenMany(notificationAdapter.findPending(Instant.now().plusSeconds(1), 10)
                    .filter(n -> n.getTransferId().equals("tx-it-3")))
        )
            .verifyComplete();
    }
}
