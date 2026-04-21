package com.fintech.transfer.port.out;

import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferStatus;
import reactor.core.publisher.Mono;

public interface TransferRepository {
    Mono<Transfer> save(Transfer transfer);
    Mono<Transfer> findById(String id);
    Mono<Transfer> updateStatus(String id, TransferStatus newStatus);
}
