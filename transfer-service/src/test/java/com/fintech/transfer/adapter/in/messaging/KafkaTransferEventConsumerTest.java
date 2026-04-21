package com.fintech.transfer.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.transfer.port.in.TransferUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.*;

class KafkaTransferEventConsumerTest {

    private final TransferUseCase useCase = mock(TransferUseCase.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private KafkaTransferEventConsumer consumer;

    @BeforeEach
    void setup() {
        consumer = new KafkaTransferEventConsumer(useCase, mapper);
    }

    private ConsumerRecord<String, String> record(String topic, String transferId) throws Exception {
        String payload = mapper.writeValueAsString(java.util.Map.of("transfer_id", transferId));
        return new ConsumerRecord<>(topic, 0, 0L, "key", payload);
    }

    @Test
    void debitCompleted_callsOnDebitCompleted() throws Exception {
        when(useCase.onDebitCompleted("tx-1")).thenReturn(Mono.empty());
        consumer.onDebitCompleted(record("debit-completed", "tx-1"), ack);
        verify(useCase).onDebitCompleted("tx-1");
        verify(ack).acknowledge();
    }

    @Test
    void creditCompleted_callsOnCreditCompleted() throws Exception {
        when(useCase.onCreditCompleted("tx-2")).thenReturn(Mono.empty());
        consumer.onCreditCompleted(record("credit-completed", "tx-2"), ack);
        verify(useCase).onCreditCompleted("tx-2");
        verify(ack).acknowledge();
    }

    @Test
    void debitFailed_callsOnDebitFailed() throws Exception {
        when(useCase.onDebitFailed("tx-3")).thenReturn(Mono.empty());
        consumer.onDebitFailed(record("debit-failed", "tx-3"), ack);
        verify(useCase).onDebitFailed("tx-3");
        verify(ack).acknowledge();
    }

    @Test
    void debitReversal_callsOnDebitReversal() throws Exception {
        when(useCase.onDebitReversal("tx-4")).thenReturn(Mono.empty());
        consumer.onDebitReversal(record("debit-reversal", "tx-4"), ack);
        verify(useCase).onDebitReversal("tx-4");
        verify(ack).acknowledge();
    }

    @Test
    void malformedPayload_acksWithoutCalling() {
        ConsumerRecord<String, String> bad = new ConsumerRecord<>("debit-completed", 0, 0L, "k", "{invalid");
        consumer.onDebitCompleted(bad, ack);
        verifyNoInteractions(useCase);
        verify(ack).acknowledge();
    }
}
