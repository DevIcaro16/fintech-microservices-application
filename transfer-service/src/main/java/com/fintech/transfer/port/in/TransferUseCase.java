package com.fintech.transfer.port.in;

import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferHistorySummary;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;

public interface TransferUseCase {
    Mono<Transfer> create(String sourceAccountId, String destinationAccountId,
                          BigDecimal amount, String callbackUrl);
    Mono<Transfer> findById(String id);
    Flux<TransferHistorySummary> findByUserId(String userId);
    Mono<Void> onDebitCompleted(String transferId);
    Mono<Void> onCreditCompleted(String transferId);
    Mono<Void> onDebitFailed(String transferId);
    Mono<Void> onDebitReversal(String transferId);
}
