package com.fintech.account.adapter.out.persistence;

import com.fintech.account.domain.OutboxEntry;
import com.fintech.account.port.out.OutboxPort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
public class OutboxRepository implements OutboxPort {

    private final R2dbcEntityTemplate template0;
    private final R2dbcEntityTemplate template1;
    private final ShardResolver shardResolver;

    public OutboxRepository(
        @Qualifier("r2dbcTemplate0") R2dbcEntityTemplate template0,
        @Qualifier("r2dbcTemplate1") R2dbcEntityTemplate template1,
        ShardResolver shardResolver
    ) {
        this.template0 = template0;
        this.template1 = template1;
        this.shardResolver = shardResolver;
    }

    private R2dbcEntityTemplate templateFor(int shard) {
        return shard == 0 ? template0 : template1;
    }

    @Override
    public Mono<Void> save(String shardKey, OutboxEntry entry) {
        int shard = shardResolver.resolve(shardKey);
        return templateFor(shard).getDatabaseClient()
            .sql("""
                INSERT INTO outbox (id, aggregate_id, event_type, payload, published, created_at)
                VALUES (:id, :aggregate_id, :event_type, :payload, false, :created_at)
                """)
            .bindValues(Map.of(
                "id", entry.getId(),
                "aggregate_id", entry.getAggregateId(),
                "event_type", entry.getEventType(),
                "payload", entry.getPayload(),
                "created_at", entry.getCreatedAt()
            ))
            .then();
    }

    @Override
    public Flux<OutboxEntry> findUnpublished(int shard, int limit) {
        return templateFor(shard).getDatabaseClient()
            .sql("SELECT id, aggregate_id, event_type, payload, created_at FROM outbox WHERE published = false ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED")
            .bind("limit", limit)
            .map(r -> new OutboxEntry(
                r.get("id", UUID.class),
                r.get("aggregate_id", String.class),
                r.get("event_type", String.class),
                r.get("payload", String.class),
                r.get("created_at", Instant.class)
            ))
            .all();
    }

    @Override
    public Mono<Void> markPublished(int shard, UUID id) {
        return templateFor(shard).getDatabaseClient()
            .sql("UPDATE outbox SET published = true WHERE id = :id")
            .bind("id", id)
            .then();
    }
}
