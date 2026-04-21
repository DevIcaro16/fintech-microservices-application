package com.fintech.transfer.port.out;

import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferHistorySummary;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TransferHistoryPort {
    Mono<Void> save(Transfer transfer);
    Flux<TransferHistorySummary> findByUserId(String userId);
}
