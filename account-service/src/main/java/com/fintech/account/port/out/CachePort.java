package com.fintech.account.port.out;

import com.fintech.account.domain.Account;
import reactor.core.publisher.Mono;

public interface CachePort {
    Mono<Account> get(String accountId);
    Mono<Void> put(Account account);
    Mono<Void> evict(String accountId);
}
