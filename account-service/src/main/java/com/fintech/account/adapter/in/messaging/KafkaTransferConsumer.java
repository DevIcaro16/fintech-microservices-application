package com.fintech.account.adapter.in.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.account.domain.Money;
import com.fintech.account.port.in.AccountUseCase;
import com.fintech.account.port.out.OutboxPort;
import com.fintech.account.domain.OutboxEntry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class KafkaTransferConsumer {

    private static final Logger log = LoggerFactory.getLogger(KafkaTransferConsumer.class);

    private final AccountUseCase accountUseCase;
    private final OutboxPort outboxPort;
    private final ObjectMapper mapper;

    public KafkaTransferConsumer(AccountUseCase accountUseCase, OutboxPort outboxPort, ObjectMapper mapper) {
        this.accountUseCase = accountUseCase;
        this.outboxPort = outboxPort;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "transfer-requested", groupId = "account-service")
    public void onTransferRequested(ConsumerRecord<String, String> record, Acknowledgment ack) {
        TransferRequestedEvent event;
        try {
            event = mapper.readValue(record.value(), TransferRequestedEvent.class);
        } catch (Exception e) {
            log.error("Failed to deserialize transfer-requested record offset={}", record.offset(), e);
            ack.acknowledge();
            return;
        }

        String transferId = event.getTransferId();
        String sourceId = event.getSourceAccountId();
        String destId = event.getDestinationAccountId();
        Money amount = Money.of(event.getAmount());

        log.info("Processing transfer transferId={} source={} dest={} amount={}",
            transferId, sourceId, destId, amount.amount());

        try {
            accountUseCase.debit(sourceId, amount, transferId).block();
        } catch (Exception e) {
            log.warn("Debit failed for transferId={}: {}", transferId, e.getMessage());
            // debit-failed already written to outbox by AccountService
            ack.acknowledge();
            return;
        }

        try {
            accountUseCase.credit(destId, amount, transferId).block();
        } catch (Exception e) {
            log.warn("Credit failed for transferId={}, initiating reversal: {}", transferId, e.getMessage());
            publishReversal(sourceId, destId, transferId, amount);
        }

        ack.acknowledge();
    }

    private void publishReversal(String sourceId, String destId, String transferId, Money amount) {
        String reversalId = transferId + ":reversal";
        try {
            // Compensate: credit back to source
            accountUseCase.credit(sourceId, amount, reversalId).block();
            log.info("Reversal credit applied for transferId={}", transferId);
        } catch (Exception e) {
            log.error("Reversal credit also failed for transferId={} — manual intervention required", transferId, e);
        }

        // Publish debit-reversal event via outbox so transfer-service is notified
        try {
            String payload = mapper.writeValueAsString(Map.of(
                "transfer_id", transferId,
                "source_account_id", sourceId,
                "destination_account_id", destId,
                "amount", amount.amount(),
                "reason", "credit-failed"
            ));
            OutboxEntry entry = new OutboxEntry(sourceId, "debit-reversal", payload);
            outboxPort.save(sourceId, entry).block();
        } catch (Exception e) {
            log.error("Failed to write debit-reversal outbox entry for transferId={}", transferId, e);
        }
    }
}
