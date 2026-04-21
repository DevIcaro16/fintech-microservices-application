package com.fintech.account.port.in;

import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import reactor.core.publisher.Mono;

public interface AccountUseCase {
    Mono<Account> createAccount(String ownerId, Money initialBalance);
    Mono<Account> findById(String accountId);
    Mono<Money> getBalance(String accountId);
    Mono<Account> debit(String accountId, Money amount, String transferId);
    Mono<Account> credit(String accountId, Money amount, String transferId);
}
