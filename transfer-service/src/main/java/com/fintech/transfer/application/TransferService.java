package com.fintech.transfer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.transfer.domain.*;
import com.fintech.transfer.port.in.TransferUseCase;
import com.fintech.transfer.port.out.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

@Service
public class TransferService implements TransferUseCase {

    private final TransferRepository transferRepo;
    private final TransferHistoryPort historyPort;
    private final NotificationPort notificationPort;
    private final EventPort eventPort;
    private final ObjectMapper mapper;

    public TransferService(TransferRepository transferRepo, TransferHistoryPort historyPort,
                           NotificationPort notificationPort, EventPort eventPort,
                           ObjectMapper mapper) {
        this.transferRepo = transferRepo;
        this.historyPort = historyPort;
        this.notificationPort = notificationPort;
        this.eventPort = eventPort;
        this.mapper = mapper;
    }

    @Override
    public Mono<Transfer> create(String sourceAccountId, String destinationAccountId,
                                 BigDecimal amount, String callbackUrl) {
        Transfer transfer = Transfer.create(sourceAccountId, destinationAccountId, amount, callbackUrl);
        return transferRepo.save(transfer)
            .flatMap(saved -> publishEvent("transfer-requested", saved.getId(), Map.of(
                "transfer_id", saved.getId(),
                "source_account_id", saved.getSourceAccountId(),
                "destination_account_id", saved.getDestinationAccountId(),
                "amount", saved.getAmount()
            )).thenReturn(saved));
    }

    @Override
    public Mono<Transfer> findById(String id) {
        return transferRepo.findById(id)
            .switchIfEmpty(Mono.error(new TransferNotFoundException(id)));
    }

    @Override
    public Flux<TransferHistorySummary> findByUserId(String userId) {
        return historyPort.findByUserId(userId);
    }

    @Override
    public Mono<Void> onDebitCompleted(String transferId) {
        return transferRepo.findById(transferId)
            .flatMap(t -> {
                if (!t.canTransitionTo(TransferStatus.DEBITED)) return Mono.empty();
                return transferRepo.updateStatus(transferId, TransferStatus.DEBITED).then();
            });
    }

    @Override
    public Mono<Void> onCreditCompleted(String transferId) {
        return transferRepo.findById(transferId)
            .flatMap(t -> {
                if (!t.canTransitionTo(TransferStatus.COMPLETED)) return Mono.empty();
                return transferRepo.updateStatus(transferId, TransferStatus.COMPLETED)
                    .flatMap(updated ->
                        historyPort.save(updated)
                            .then(scheduleNotification(updated, "COMPLETED"))
                            .then(publishEvent("transfer-completed", transferId, Map.of(
                                "transfer_id", transferId,
                                "status", "COMPLETED",
                                "source_account_id", updated.getSourceAccountId(),
                                "destination_account_id", updated.getDestinationAccountId(),
                                "amount", updated.getAmount().toPlainString()
                            )))
                    );
            });
    }

    @Override
    public Mono<Void> onDebitFailed(String transferId) {
        return transferRepo.findById(transferId)
            .flatMap(t -> {
                if (!t.canTransitionTo(TransferStatus.FAILED)) return Mono.empty();
                return transferRepo.updateStatus(transferId, TransferStatus.FAILED)
                    .flatMap(updated ->
                        historyPort.save(updated)
                            .then(scheduleNotification(updated, "FAILED"))
                            .then(publishEvent("transfer-failed", transferId, Map.of(
                                "transfer_id", transferId,
                                "status", "FAILED",
                                "source_account_id", updated.getSourceAccountId(),
                                "destination_account_id", updated.getDestinationAccountId(),
                                "amount", updated.getAmount().toPlainString()
                            )))
                    );
            });
    }

    @Override
    public Mono<Void> onDebitReversal(String transferId) {
        return transferRepo.findById(transferId)
            .flatMap(t -> {
                if (!t.canTransitionTo(TransferStatus.REVERTING)) return Mono.empty();
                return transferRepo.updateStatus(transferId, TransferStatus.REVERTING)
                    .then(transferRepo.updateStatus(transferId, TransferStatus.FAILED))
                    .flatMap(updated ->
                        historyPort.save(updated)
                            .then(scheduleNotification(updated, "FAILED"))
                            .then(publishEvent("transfer-failed", transferId, Map.of(
                                "transfer_id", transferId,
                                "status", "FAILED",
                                "source_account_id", updated.getSourceAccountId(),
                                "destination_account_id", updated.getDestinationAccountId(),
                                "amount", updated.getAmount().toPlainString()
                            )))
                    );
            });
    }

    private Mono<Void> scheduleNotification(Transfer transfer, String status) {
        if (transfer.getCallbackUrl() == null) return Mono.empty();
        return Mono.fromCallable(() -> {
            String payload = mapper.writeValueAsString(Map.of(
                "transfer_id", transfer.getId(),
                "status", status,
                "source_account_id", transfer.getSourceAccountId(),
                "destination_account_id", transfer.getDestinationAccountId(),
                "amount", transfer.getAmount().toPlainString()
            ));
            return NotificationEntry.pending(transfer.getId(), transfer.getCallbackUrl(), payload);
        }).flatMap(notificationPort::save);
    }

    private Mono<Void> publishEvent(String topic, String key, Map<String, Object> payload) {
        return Mono.fromCallable(() -> mapper.writeValueAsString(payload))
            .flatMap(json -> eventPort.publish(topic, key, json));
    }

    public static class TransferNotFoundException extends RuntimeException {
        public TransferNotFoundException(String id) { super("Transfer not found: " + id); }
    }
}
