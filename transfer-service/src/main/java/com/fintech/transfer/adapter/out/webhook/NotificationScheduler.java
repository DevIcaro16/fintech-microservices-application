package com.fintech.transfer.adapter.out.webhook;

import com.fintech.transfer.domain.NotificationEntry;
import com.fintech.transfer.port.out.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@EnableScheduling
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private static final int MAX_ATTEMPTS = 5;
    private static final long[] BACKOFF_SECONDS = {1, 2, 4, 8, 16};

    private final NotificationPort notificationPort;
    private final WebhookNotifier webhookNotifier;

    public NotificationScheduler(NotificationPort notificationPort, WebhookNotifier webhookNotifier) {
        this.notificationPort = notificationPort;
        this.webhookNotifier = webhookNotifier;
    }

    @Scheduled(fixedDelay = 5000)
    public void flush() {
        notificationPort.findPending(Instant.now(), 50)
            .flatMap(entry -> webhookNotifier.deliver(entry)
                .flatMap(success -> {
                    if (success) {
                        return notificationPort.markDelivered(entry.getTransferId(), entry.getCreatedAt());
                    }
                    int nextAttempts = entry.getAttempts() + 1;
                    if (nextAttempts >= MAX_ATTEMPTS) {
                        log.error("Webhook exhausted after {} attempts for transferId={}",
                            MAX_ATTEMPTS, entry.getTransferId());
                        return notificationPort.markExhausted(entry.getTransferId(), entry.getCreatedAt());
                    }
                    long delaySec = BACKOFF_SECONDS[Math.min(nextAttempts, BACKOFF_SECONDS.length - 1)];
                    NotificationEntry retryEntry = entry.withNextAttempt(Instant.now().plusSeconds(delaySec));
                    return notificationPort.updateAttempt(retryEntry);
                })
            )
            .subscribe(
                null,
                ex -> log.error("Notification scheduler error", ex)
            );
    }
}
