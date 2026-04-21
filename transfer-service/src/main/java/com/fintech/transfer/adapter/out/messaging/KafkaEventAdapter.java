package com.fintech.transfer.adapter.out.messaging;

import com.fintech.transfer.port.out.EventPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class KafkaEventAdapter implements EventPort {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaEventAdapter(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public Mono<Void> publish(String topic, String key, String payload) {
        return Mono.fromFuture(kafkaTemplate.send(topic, key, payload).toCompletableFuture())
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }
}
