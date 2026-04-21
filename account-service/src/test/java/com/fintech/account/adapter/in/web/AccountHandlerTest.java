package com.fintech.account.adapter.in.web;

import com.fintech.account.application.AccountService;
import com.fintech.account.config.RouterConfig;
import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.in.AccountUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@WebFluxTest
@Import({AccountHandler.class, RouterConfig.class})
class AccountHandlerTest {

    @Autowired
    WebTestClient client;

    @MockBean
    AccountUseCase useCase;

    private Account sampleAccount() {
        return new Account("acc-1", "owner-1", Money.of(BigDecimal.valueOf(500)), Instant.now());
    }

    @Test
    void createAccount_returns201() {
        when(useCase.createAccount(eq("owner-1"), any())).thenReturn(Mono.just(sampleAccount()));

        client.post().uri("/accounts")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("owner_id", "owner-1", "initial_balance", "500"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody()
            .jsonPath("$.id").isEqualTo("acc-1")
            .jsonPath("$.balance").isEqualTo(500);
    }

    @Test
    void getAccount_returns200() {
        when(useCase.findById("acc-1")).thenReturn(Mono.just(sampleAccount()));

        client.get().uri("/accounts/acc-1")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.id").isEqualTo("acc-1")
            .jsonPath("$.owner_id").isEqualTo("owner-1");
    }

    @Test
    void getAccount_returns404_whenNotFound() {
        when(useCase.findById("missing")).thenReturn(
            Mono.error(new AccountService.AccountNotFoundException("missing")));

        client.get().uri("/accounts/missing")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void getBalance_returns200() {
        when(useCase.getBalance("acc-1")).thenReturn(Mono.just(Money.of(BigDecimal.valueOf(500))));

        client.get().uri("/accounts/acc-1/balance")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.balance").isEqualTo(500);
    }

    @Test
    void debit_returns200() {
        when(useCase.debit(eq("acc-1"), any(), eq("tx-1"))).thenReturn(Mono.just(sampleAccount()));

        client.post().uri("/accounts/acc-1/debit")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("amount", "100", "transfer_id", "tx-1"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.account_id").isEqualTo("acc-1");
    }

    @Test
    void debit_returns400_whenInsufficientFunds() {
        when(useCase.debit(eq("acc-1"), any(), eq("tx-1"))).thenReturn(
            Mono.error(new Account.InsufficientFundsException("not enough")));

        client.post().uri("/accounts/acc-1/debit")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("amount", "9999", "transfer_id", "tx-1"))
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.error").isEqualTo("insufficient funds");
    }

    @Test
    void debit_returns409_onDuplicateTransfer() {
        when(useCase.debit(eq("acc-1"), any(), eq("tx-dup"))).thenReturn(
            Mono.error(new AccountService.DuplicateTransferException("tx-dup")));

        client.post().uri("/accounts/acc-1/debit")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("amount", "10", "transfer_id", "tx-dup"))
            .exchange()
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error").isEqualTo("duplicate transfer_id");
    }

    @Test
    void credit_returns200() {
        when(useCase.credit(eq("acc-1"), any(), eq("tx-2"))).thenReturn(Mono.just(sampleAccount()));

        client.post().uri("/accounts/acc-1/credit")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("amount", "200", "transfer_id", "tx-2"))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.account_id").isEqualTo("acc-1");
    }
}
