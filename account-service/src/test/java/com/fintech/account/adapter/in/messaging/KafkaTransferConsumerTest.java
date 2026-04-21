package com.fintech.account.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.account.application.AccountService;
import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.in.AccountUseCase;
import com.fintech.account.port.out.OutboxPort;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KafkaTransferConsumerTest {

    private final AccountUseCase accountUseCase = mock(AccountUseCase.class);
    private final OutboxPort outboxPort = mock(OutboxPort.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Acknowledgment ack = mock(Acknowledgment.class);

    private KafkaTransferConsumer consumer;

    @BeforeEach
    void setup() {
        consumer = new KafkaTransferConsumer(accountUseCase, outboxPort, mapper);
    }

    private Account accountWith(String id, BigDecimal balance) {
        return new Account(id, "owner", Money.of(balance), Instant.now());
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("transfer-requested", 0, 0L, "key", payload);
    }

    @Test
    void happyPath_debitAndCreditSucceed() throws Exception {
        String payload = mapper.writeValueAsString(java.util.Map.of(
            "transfer_id", "tx-1",
            "source_account_id", "src-1",
            "destination_account_id", "dst-1",
            "amount", "100.00"
        ));

        when(accountUseCase.debit(eq("src-1"), any(), eq("tx-1")))
            .thenReturn(Mono.just(accountWith("src-1", BigDecimal.valueOf(400))));
        when(accountUseCase.credit(eq("dst-1"), any(), eq("tx-1")))
            .thenReturn(Mono.just(accountWith("dst-1", BigDecimal.valueOf(600))));

        consumer.onTransferRequested(record(payload), ack);

        verify(accountUseCase).debit(eq("src-1"), any(), eq("tx-1"));
        verify(accountUseCase).credit(eq("dst-1"), any(), eq("tx-1"));
        verify(ack).acknowledge();
        verifyNoInteractions(outboxPort);
    }

    @Test
    void debitFails_acksAndSkipsCredit() throws Exception {
        String payload = mapper.writeValueAsString(java.util.Map.of(
            "transfer_id", "tx-2",
            "source_account_id", "src-2",
            "destination_account_id", "dst-2",
            "amount", "999.00"
        ));

        when(accountUseCase.debit(eq("src-2"), any(), eq("tx-2")))
            .thenReturn(Mono.error(new Account.InsufficientFundsException("not enough")));

        consumer.onTransferRequested(record(payload), ack);

        verify(accountUseCase).debit(eq("src-2"), any(), eq("tx-2"));
        verify(accountUseCase, never()).credit(any(), any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void creditFails_publishesReversalAndAcks() throws Exception {
        String payload = mapper.writeValueAsString(java.util.Map.of(
            "transfer_id", "tx-3",
            "source_account_id", "src-3",
            "destination_account_id", "dst-3",
            "amount", "50.00"
        ));

        when(accountUseCase.debit(eq("src-3"), any(), eq("tx-3")))
            .thenReturn(Mono.just(accountWith("src-3", BigDecimal.valueOf(450))));
        when(accountUseCase.credit(eq("dst-3"), any(), eq("tx-3")))
            .thenReturn(Mono.error(new AccountService.AccountNotFoundException("dst-3")));
        when(accountUseCase.credit(eq("src-3"), any(), eq("tx-3:reversal")))
            .thenReturn(Mono.just(accountWith("src-3", BigDecimal.valueOf(500))));
        when(outboxPort.save(anyString(), any())).thenReturn(Mono.empty());

        consumer.onTransferRequested(record(payload), ack);

        verify(accountUseCase).debit(eq("src-3"), any(), eq("tx-3"));
        verify(accountUseCase).credit(eq("dst-3"), any(), eq("tx-3"));
        verify(accountUseCase).credit(eq("src-3"), any(), eq("tx-3:reversal"));
        verify(outboxPort).save(eq("src-3"), argThat(e -> e.getEventType().equals("debit-reversal")));
        verify(ack).acknowledge();
    }

    @Test
    void malformedPayload_acksWithoutProcessing() {
        consumer.onTransferRequested(record("{invalid-json"), ack);

        verifyNoInteractions(accountUseCase);
        verifyNoInteractions(outboxPort);
        verify(ack).acknowledge();
    }
}
