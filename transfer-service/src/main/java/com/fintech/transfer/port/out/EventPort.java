package com.fintech.transfer.port.out;

import reactor.core.publisher.Mono;

public interface EventPort {
    Mono<Void> publish(String topic, String key, String payload);
}
