package com.fintech.account.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.out.AccountPort;
import com.fintech.account.port.out.CachePort;
import com.fintech.account.port.out.OutboxPort;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {

    private final AccountPort accountPort = mock(AccountPort.class);
    private final CachePort cachePort = mock(CachePort.class);
    private final OutboxPort outboxPort = mock(OutboxPort.class);
    private final AccountService service = new AccountService(accountPort, cachePort, outboxPort, new ObjectMapper());

    @Test
    void debit_shouldReduceBalance_whenFundsAreAvailable() {
        Account account = Account.create("owner-1", Money.of(BigDecimal.valueOf(200)));
        when(accountPort.existsTransfer(anyString(), anyString())).thenReturn(Mono.just(false));
        when(accountPort.findById(account.getId())).thenReturn(Mono.just(account));
        when(accountPort.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(accountPort.saveTransferIdempotency(anyString(), anyString())).thenReturn(Mono.empty());
        when(outboxPort.save(anyString(), any())).thenReturn(Mono.empty());
        when(cachePort.put(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.debit(account.getId(), Money.of(BigDecimal.valueOf(50)), "tx-1"))
            .assertNext(a -> {
                assert a.getBalance().amount().compareTo(BigDecimal.valueOf(150)) == 0;
            })
            .verifyComplete();
    }

    @Test
    void debit_shouldFail_whenInsufficientFunds() {
        Account account = Account.create("owner-2", Money.of(BigDecimal.valueOf(10)));
        when(accountPort.existsTransfer(anyString(), anyString())).thenReturn(Mono.just(false));
        when(accountPort.findById(account.getId())).thenReturn(Mono.just(account));
        when(outboxPort.save(anyString(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.debit(account.getId(), Money.of(BigDecimal.valueOf(100)), "tx-2"))
            .expectError(Account.InsufficientFundsException.class)
            .verify();
    }

    @Test
    void credit_shouldIncreaseBalance() {
        Account account = Account.create("owner-3", Money.of(BigDecimal.valueOf(100)));
        when(accountPort.existsTransfer(anyString(), anyString())).thenReturn(Mono.just(false));
        when(accountPort.findById(account.getId())).thenReturn(Mono.just(account));
        when(accountPort.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(accountPort.saveTransferIdempotency(anyString(), anyString())).thenReturn(Mono.empty());
        when(outboxPort.save(anyString(), any())).thenReturn(Mono.empty());
        when(cachePort.put(any())).thenReturn(Mono.empty());

        StepVerifier.create(service.credit(account.getId(), Money.of(BigDecimal.valueOf(50)), "tx-3"))
            .assertNext(a -> {
                assert a.getBalance().amount().compareTo(BigDecimal.valueOf(150)) == 0;
            })
            .verifyComplete();
    }

    @Test
    void debit_shouldRejectDuplicateTransfer() {
        Account account = Account.create("owner-4", Money.of(BigDecimal.valueOf(500)));
        when(accountPort.existsTransfer(account.getId(), "tx-dup")).thenReturn(Mono.just(true));

        StepVerifier.create(service.debit(account.getId(), Money.of(BigDecimal.valueOf(10)), "tx-dup"))
            .expectError(AccountService.DuplicateTransferException.class)
            .verify();
    }
}
