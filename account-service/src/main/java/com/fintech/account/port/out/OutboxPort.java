package com.fintech.account.port.out;

import com.fintech.account.domain.OutboxEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OutboxPort {
    Mono<Void> save(String shardKey, OutboxEntry entry);
    Flux<OutboxEntry> findUnpublished(int shard, int limit);
    Mono<Void> markPublished(int shard, java.util.UUID id);
}
