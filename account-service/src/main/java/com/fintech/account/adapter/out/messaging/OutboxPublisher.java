package com.fintech.account.adapter.out.messaging;

import com.fintech.account.port.out.EventPort;
import com.fintech.account.port.out.OutboxPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@EnableScheduling
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int BATCH_SIZE = 100;

    private final OutboxPort outboxPort;
    private final EventPort eventPort;

    public OutboxPublisher(OutboxPort outboxPort, EventPort eventPort) {
        this.outboxPort = outboxPort;
        this.eventPort = eventPort;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishShard0() {
        publishShard(0).subscribe(
            null,
            ex -> log.error("Outbox flush failed for shard 0", ex)
        );
    }

    @Scheduled(fixedDelay = 1000)
    public void publishShard1() {
        publishShard(1).subscribe(
            null,
            ex -> log.error("Outbox flush failed for shard 1", ex)
        );
    }

    private Flux<Void> publishShard(int shard) {
        return outboxPort.findUnpublished(shard, BATCH_SIZE)
            .concatMap(entry ->
                eventPort.publish(entry.getEventType(), entry.getAggregateId(), entry.getPayload())
                    .then(outboxPort.markPublished(shard, entry.getId()))
                    .doOnSuccess(v -> log.debug("Published outbox entry {} ({})", entry.getId(), entry.getEventType()))
                    .doOnError(ex -> log.warn("Failed to publish outbox entry {}: {}", entry.getId(), ex.getMessage()))
            );
    }
}
