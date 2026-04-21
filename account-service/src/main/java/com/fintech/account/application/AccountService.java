package com.fintech.account.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.domain.OutboxEntry;
import com.fintech.account.port.in.AccountUseCase;
import com.fintech.account.port.out.AccountPort;
import com.fintech.account.port.out.CachePort;
import com.fintech.account.port.out.OutboxPort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class AccountService implements AccountUseCase {

    private final AccountPort accountPort;
    private final CachePort cachePort;
    private final OutboxPort outboxPort;
    private final ObjectMapper mapper;

    public AccountService(AccountPort accountPort, CachePort cachePort, OutboxPort outboxPort, ObjectMapper mapper) {
        this.accountPort = accountPort;
        this.cachePort = cachePort;
        this.outboxPort = outboxPort;
        this.mapper = mapper;
    }

    @Override
    public Mono<Account> createAccount(String ownerId, Money initialBalance) {
        Account account = Account.create(ownerId, initialBalance);
        return accountPort.save(account)
            .flatMap(saved ->
                outboxEntry("account.created", saved.getId(),
                    Map.of("account_id", saved.getId(), "owner_id", saved.getOwnerId()))
                    .flatMap(entry -> outboxPort.save(saved.getId(), entry))
                    .then(cachePort.put(saved))
                    .thenReturn(saved)
            );
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
                            return outboxEntry("debit.failed", accountId,
                                Map.of("account_id", accountId, "transfer_id", transferId,
                                       "reason", "insufficient_funds"))
                                .flatMap(entry -> outboxPort.save(accountId, entry))
                                .then(Mono.error(e));
                        }
                        return accountPort.save(account)
                            .flatMap(saved ->
                                accountPort.saveTransferIdempotency(accountId, transferId)
                                    .then(outboxEntry("debit.completed", accountId,
                                        Map.of("account_id", accountId, "transfer_id", transferId,
                                               "new_balance", saved.getBalance().amount())))
                                    .flatMap(entry -> outboxPort.save(accountId, entry))
                                    .then(cachePort.put(saved))
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
                                    .then(outboxEntry("credit.completed", accountId,
                                        Map.of("account_id", accountId, "transfer_id", transferId,
                                               "new_balance", saved.getBalance().amount())))
                                    .flatMap(entry -> outboxPort.save(accountId, entry))
                                    .then(cachePort.put(saved))
                                    .thenReturn(saved)
                            );
                    });
            });
    }

    private Mono<OutboxEntry> outboxEntry(String eventType, String aggregateId, Map<String, Object> payload) {
        return Mono.fromCallable(() ->
            new OutboxEntry(aggregateId, eventType, mapper.writeValueAsString(payload))
        );
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String id) { super("Account not found: " + id); }
    }

    public static class DuplicateTransferException extends RuntimeException {
        public DuplicateTransferException(String transferId) { super("Duplicate transfer: " + transferId); }
    }
}
