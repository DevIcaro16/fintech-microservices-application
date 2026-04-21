package com.fintech.transfer.adapter.in.web;

import com.fintech.transfer.application.TransferService;
import com.fintech.transfer.config.RouterConfig;
import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferStatus;
import com.fintech.transfer.port.in.TransferUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@WebFluxTest
@Import({TransferHandler.class, RouterConfig.class})
class TransferHandlerTest {

    @Autowired WebTestClient client;
    @MockBean TransferUseCase useCase;

    private Transfer sampleTransfer() {
        return new Transfer("tx-1", "src-1", "dst-1",
            BigDecimal.valueOf(100), TransferStatus.PENDING,
            "http://cb", "src-1", Instant.now(), Instant.now());
    }

    @Test
    void post_returns202() {
        when(useCase.create(eq("src-1"), eq("dst-1"), any(), eq("http://cb")))
            .thenReturn(Mono.just(sampleTransfer()));

        client.post().uri("/transfers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "source_account_id", "src-1",
                "destination_account_id", "dst-1",
                "amount", "100.00",
                "callback_url", "http://cb"
            ))
            .exchange()
            .expectStatus().isAccepted()
            .expectBody()
            .jsonPath("$.transfer_id").isEqualTo("tx-1")
            .jsonPath("$.status").isEqualTo("PENDING");
    }

    @Test
    void post_returns400_whenSameSourceAndDest() {
        client.post().uri("/transfers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "source_account_id", "same",
                "destination_account_id", "same",
                "amount", "100.00"
            ))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void post_returns400_whenAmountZero() {
        client.post().uri("/transfers")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "source_account_id", "src-1",
                "destination_account_id", "dst-1",
                "amount", "0"
            ))
            .exchange()
            .expectStatus().isBadRequest();
    }

    @Test
    void getById_returns200() {
        when(useCase.findById("tx-1")).thenReturn(Mono.just(sampleTransfer()));

        client.get().uri("/transfers/tx-1")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.transfer_id").isEqualTo("tx-1")
            .jsonPath("$.status").isEqualTo("PENDING");
    }

    @Test
    void getById_returns404() {
        when(useCase.findById("missing"))
            .thenReturn(Mono.error(new TransferService.TransferNotFoundException("missing")));

        client.get().uri("/transfers/missing")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void getByUser_returns200() {
        when(useCase.findByUserId("src-1")).thenReturn(Flux.empty());

        client.get().uri("/transfers?user_id=src-1")
            .exchange()
            .expectStatus().isOk();
    }
}
