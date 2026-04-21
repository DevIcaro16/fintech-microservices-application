package com.fintech.account.application;

import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.in.AccountUseCase;
import com.fintech.account.port.out.AccountPort;
import com.fintech.account.port.out.CachePort;
import com.fintech.account.port.out.EventPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class AccountService implements AccountUseCase {

    private final AccountPort accountPort;
    private final CachePort cachePort;
    private final EventPort eventPort;
    private final ObjectMapper mapper;

    public AccountService(AccountPort accountPort, CachePort cachePort, EventPort eventPort, ObjectMapper mapper) {
        this.accountPort = accountPort;
        this.cachePort = cachePort;
        this.eventPort = eventPort;
        this.mapper = mapper;
    }

    @Override
    public Mono<Account> createAccount(String ownerId, Money initialBalance) {
        Account account = Account.create(ownerId, initialBalance);
        return accountPort.save(account)
            .flatMap(saved -> cachePort.put(saved).thenReturn(saved));
    }

    @Override
    public Mono<Account> findById(String accountId) {
        return cachePort.get(accountId)
            .switchIfEmpty(
                accountPort.findById(accountId)
                    .switchIfEmpty(Mono.error(new AccountNotFoundException(accountId)))
                    .flatMap(account -> cachePort.put(account).thenReturn(account))
            );
    }

    @Override
    public Mono<Money> getBalance(String accountId) {
        return findById(accountId).map(Account::getBalance);
    }

    @Override
    public Mono<Account> debit(String accountId, Money amount, String transferId) {
        return accountPort.existsTransfer(accountId, transferId)
            .flatMap(exists -> {
                if (exists) return Mono.error(new DuplicateTransferException(transferId));
                return accountPort.findById(accountId)
                    .switchIfEmpty(Mono.error(new AccountNotFoundException(accountId)))
                    .flatMap(account -> {
                        try {
                            account.debit(amount);
                        } catch (Account.InsufficientFundsException e) {
                            return publishEvent("debit.failed", accountId,
                                Map.of("account_id", accountId, "transfer_id", transferId,
                                       "reason", "insufficient_funds"))
                                .then(Mono.error(e));
                        }
                        return accountPort.save(account)
                            .flatMap(saved ->
                                accountPort.saveTransferIdempotency(accountId, transferId)
                                    .then(cachePort.put(saved))
                                    .then(publishEvent("debit.completed", accountId,
                                        Map.of("account_id", accountId, "transfer_id", transferId,
                                               "new_balance", saved.getBalance().amount())))
                                    .thenReturn(saved)
                            );
                    });
            });
    }

    @Override
    public Mono<Account> credit(String accountId, Money amount, String transferId) {
        return accountPort.existsTransfer(accountId, transferId)
            .flatMap(exists -> {
                if (exists) return Mono.error(new DuplicateTransferException(transferId));
                return accountPort.findById(accountId)
                    .switchIfEmpty(Mono.error(new AccountNotFoundException(accountId)))
                    .flatMap(account -> {
                        account.credit(amount);
                        return accountPort.save(account)
                            .flatMap(saved ->
                                accountPort.saveTransferIdempotency(accountId, transferId)
                                    .then(cachePort.put(saved))
                                    .then(publishEvent("credit.completed", accountId,
                                        Map.of("account_id", accountId, "transfer_id", transferId,
                                               "new_balance", saved.getBalance().amount())))
                                    .thenReturn(saved)
                            );
                    });
            });
    }

    private Mono<Void> publishEvent(String topic, String key, Map<String, Object> payload) {
        try {
            return eventPort.publish(topic, key, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String id) { super("Account not found: " + id); }
    }

    public static class DuplicateTransferException extends RuntimeException {
        public DuplicateTransferException(String transferId) { super("Duplicate transfer: " + transferId); }
    }
}
