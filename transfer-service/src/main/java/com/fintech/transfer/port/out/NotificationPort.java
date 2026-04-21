package com.fintech.transfer.port.out;

import com.fintech.transfer.domain.NotificationEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface NotificationPort {
    Mono<Void> save(NotificationEntry entry);
    Flux<NotificationEntry> findPending(Instant now, int limit);
    Mono<Void> markDelivered(String transferId, String createdAt);
    Mono<Void> markExhausted(String transferId, String createdAt);
    Mono<Void> updateAttempt(NotificationEntry entry);
}
