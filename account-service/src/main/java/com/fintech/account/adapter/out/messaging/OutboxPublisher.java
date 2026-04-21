package com.fintech.account.adapter.out.messaging;

import com.fintech.account.port.out.EventPort;
import com.fintech.account.port.out.OutboxPort;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@EnableScheduling
public class OutboxPublisher {

    private static final int BATCH_SIZE = 100;

    private final OutboxPort outboxPort;
    private final EventPort eventPort;

    public OutboxPublisher(OutboxPort outboxPort, EventPort eventPort) {
        this.outboxPort = outboxPort;
        this.eventPort = eventPort;
    }

    @Scheduled(fixedDelay = 1000)
    public void publishShard0() {
        publishShard(0).subscribe();
    }

    @Scheduled(fixedDelay = 1000)
    public void publishShard1() {
        publishShard(1).subscribe();
    }

    private Flux<Void> publishShard(int shard) {
        return outboxPort.findUnpublished(shard, BATCH_SIZE)
            .flatMap(entry ->
                eventPort.publish(entry.getEventType(), entry.getAggregateId(), entry.getPayload())
                    .then(outboxPort.markPublished(shard, entry.getId()))
            );
    }
}
