package com.fintech.transfer.adapter.out.webhook;

import com.fintech.transfer.domain.NotificationEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private final WebClient webClient;

    public WebhookNotifier(WebClient.Builder builder) {
        this.webClient = builder.build();
    }

    public Mono<Boolean> deliver(NotificationEntry entry) {
        return webClient.post()
            .uri(entry.getCallbackUrl())
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(entry.getPayload())
            .retrieve()
            .toBodilessEntity()
            .timeout(Duration.ofSeconds(5))
            .thenReturn(true)
            .onErrorResume(e -> {
                log.warn("Webhook delivery failed transferId={} attempt={}: {}",
                    entry.getTransferId(), entry.getAttempts(), e.getMessage());
                return Mono.just(false);
            });
    }
}
