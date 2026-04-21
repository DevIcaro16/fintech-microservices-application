package com.fintech.transfer.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferStatus;
import com.fintech.transfer.port.out.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransferServiceTest {

    private final TransferRepository transferRepo = mock(TransferRepository.class);
    private final TransferHistoryPort historyPort = mock(TransferHistoryPort.class);
    private final NotificationPort notificationPort = mock(NotificationPort.class);
    private final EventPort eventPort = mock(EventPort.class);
    private final TransferService service = new TransferService(
        transferRepo, historyPort, notificationPort, eventPort, new ObjectMapper());

    @Test
    void create_savesAndPublishesTransferRequested() {
        when(transferRepo.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(eventPort.publish(eq("transfer-requested"), anyString(), anyString()))
            .thenReturn(Mono.empty());

        StepVerifier.create(service.create("src-1", "dst-1", BigDecimal.valueOf(100), "http://cb"))
            .assertNext(t -> {
                assert t.getStatus() == TransferStatus.PENDING;
                assert t.getSourceAccountId().equals("src-1");
            })
            .verifyComplete();

        verify(eventPort).publish(eq("transfer-requested"), anyString(), anyString());
    }

    @Test
    void onDebitCompleted_transitionsToDEBITED() {
        Transfer pending = Transfer.create("src-1", "dst-1", BigDecimal.valueOf(50), null);
        when(transferRepo.findById(pending.getId())).thenReturn(Mono.just(pending));
        when(transferRepo.updateStatus(pending.getId(), TransferStatus.DEBITED))
            .thenReturn(Mono.just(pending.withStatus(TransferStatus.DEBITED)));

        StepVerifier.create(service.onDebitCompleted(pending.getId()))
            .verifyComplete();

        verify(transferRepo).updateStatus(pending.getId(), TransferStatus.DEBITED);
    }

    @Test
    void onCreditCompleted_transitionsToCOMPLETEDAndSchedulesWebhook() {
        Transfer debited = Transfer.create("src-1", "dst-1", BigDecimal.valueOf(50), "http://cb")
            .withStatus(TransferStatus.DEBITED);
        when(transferRepo.findById(debited.getId())).thenReturn(Mono.just(debited));
        when(transferRepo.updateStatus(debited.getId(), TransferStatus.COMPLETED))
            .thenReturn(Mono.just(debited.withStatus(TransferStatus.COMPLETED)));
        when(historyPort.save(any())).thenReturn(Mono.empty());
        when(notificationPort.save(any())).thenReturn(Mono.empty());
        when(eventPort.publish(eq("transfer-completed"), anyString(), anyString()))
            .thenReturn(Mono.empty());

        StepVerifier.create(service.onCreditCompleted(debited.getId()))
            .verifyComplete();

        verify(notificationPort).save(argThat(n -> n.getCallbackUrl().equals("http://cb")));
        verify(historyPort).save(any());
        verify(eventPort).publish(eq("transfer-completed"), anyString(), anyString());
    }

    @Test
    void onDebitFailed_transitionsToFAILEDAndSchedulesWebhook() {
        Transfer pending = Transfer.create("src-1", "dst-1", BigDecimal.valueOf(50), "http://cb");
        when(transferRepo.findById(pending.getId())).thenReturn(Mono.just(pending));
        when(transferRepo.updateStatus(pending.getId(), TransferStatus.FAILED))
            .thenReturn(Mono.just(pending.withStatus(TransferStatus.FAILED)));
        when(historyPort.save(any())).thenReturn(Mono.empty());
        when(notificationPort.save(any())).thenReturn(Mono.empty());
        when(eventPort.publish(eq("transfer-failed"), anyString(), anyString()))
            .thenReturn(Mono.empty());

        StepVerifier.create(service.onDebitFailed(pending.getId()))
            .verifyComplete();

        verify(notificationPort).save(argThat(n -> n.getStatus().equals("PENDING")));
        verify(eventPort).publish(eq("transfer-failed"), anyString(), anyString());
    }

    @Test
    void onDebitReversal_transitionsToREVERTINGThenFAILED() {
        Transfer debited = Transfer.create("src-1", "dst-1", BigDecimal.valueOf(50), "http://cb")
            .withStatus(TransferStatus.DEBITED);
        when(transferRepo.findById(debited.getId())).thenReturn(Mono.just(debited));
        when(transferRepo.updateStatus(debited.getId(), TransferStatus.REVERTING))
            .thenReturn(Mono.just(debited.withStatus(TransferStatus.REVERTING)));
        when(transferRepo.updateStatus(debited.getId(), TransferStatus.FAILED))
            .thenReturn(Mono.just(debited.withStatus(TransferStatus.FAILED)));
        when(historyPort.save(any())).thenReturn(Mono.empty());
        when(notificationPort.save(any())).thenReturn(Mono.empty());
        when(eventPort.publish(eq("transfer-failed"), anyString(), anyString()))
            .thenReturn(Mono.empty());

        StepVerifier.create(service.onDebitReversal(debited.getId()))
            .verifyComplete();

        verify(transferRepo).updateStatus(debited.getId(), TransferStatus.REVERTING);
        verify(transferRepo).updateStatus(debited.getId(), TransferStatus.FAILED);
        verify(eventPort).publish(eq("transfer-failed"), anyString(), anyString());
    }
}
