package com.fintech.account.port.out;

import com.fintech.account.domain.Account;
import reactor.core.publisher.Mono;

public interface AccountPort {
    Mono<Account> save(Account account);
    Mono<Account> findById(String accountId);
    Mono<Boolean> existsTransfer(String accountId, String transferId);
    Mono<Void> saveTransferIdempotency(String accountId, String transferId);
}
