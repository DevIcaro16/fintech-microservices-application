package com.fintech.transfer.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.transfer.port.in.TransferUseCase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class KafkaTransferEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransferEventConsumer.class);

    private final TransferUseCase useCase;
    private final ObjectMapper mapper;

    public KafkaTransferEventConsumer(TransferUseCase useCase, ObjectMapper mapper) {
        this.useCase = useCase;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "debit-completed", groupId = "transfer-service")
    public void onDebitCompleted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handle(record, ack, transferId -> useCase.onDebitCompleted(transferId).block());
    }

    @KafkaListener(topics = "credit-completed", groupId = "transfer-service")
    public void onCreditCompleted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handle(record, ack, transferId -> useCase.onCreditCompleted(transferId).block());
    }

    @KafkaListener(topics = "debit-failed", groupId = "transfer-service")
    public void onDebitFailed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handle(record, ack, transferId -> useCase.onDebitFailed(transferId).block());
    }

    @KafkaListener(topics = "debit-reversal", groupId = "transfer-service")
    public void onDebitReversal(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handle(record, ack, transferId -> useCase.onDebitReversal(transferId).block());
    }

    private void handle(ConsumerRecord<String, String> record, Acknowledgment ack,
                        java.util.function.Consumer<String> action) {
        try {
            TransferEvent event = mapper.readValue(record.value(), TransferEvent.class);
            action.accept(event.getTransferId());
        } catch (Exception e) {
            log.error("Failed to process {} offset={}: {}", record.topic(), record.offset(), e.getMessage());
        } finally {
            ack.acknowledge();
        }
    }
}
