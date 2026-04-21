package com.fintech.transfer.adapter.in.web;

import com.fintech.transfer.application.TransferService;
import com.fintech.transfer.port.in.TransferUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class TransferHandler {

    private final TransferUseCase useCase;

    public TransferHandler(TransferUseCase useCase) {
        this.useCase = useCase;
    }

    @SuppressWarnings("unchecked")
    public Mono<ServerResponse> create(ServerRequest req) {
        return req.bodyToMono(Map.class).flatMap(body -> {
            String src = (String) body.get("source_account_id");
            String dst = (String) body.get("destination_account_id");
            String cbUrl = (String) body.get("callback_url");
            Object amountRaw = body.get("amount");

            if (src == null || dst == null || amountRaw == null)
                return ServerResponse.badRequest().bodyValue(Map.of("error", "missing fields"));
            if (src.equals(dst))
                return ServerResponse.badRequest().bodyValue(Map.of("error", "source and destination must differ"));

            BigDecimal amount;
            try { amount = new BigDecimal(amountRaw.toString()); }
            catch (Exception e) { return ServerResponse.badRequest().bodyValue(Map.of("error", "invalid amount")); }
            if (amount.compareTo(BigDecimal.ZERO) <= 0)
                return ServerResponse.badRequest().bodyValue(Map.of("error", "amount must be positive"));

            return useCase.create(src, dst, amount, cbUrl)
                .flatMap(t -> ServerResponse.status(HttpStatus.ACCEPTED).bodyValue(Map.of(
                    "transfer_id", t.getId(),
                    "status", t.getStatus().name()
                )));
        });
    }

    public Mono<ServerResponse> getById(ServerRequest req) {
        return useCase.findById(req.pathVariable("id"))
            .flatMap(t -> ServerResponse.ok().bodyValue(Map.of(
                "transfer_id", t.getId(),
                "status", t.getStatus().name(),
                "source_account_id", t.getSourceAccountId(),
                "destination_account_id", t.getDestinationAccountId(),
                "amount", t.getAmount(),
                "created_at", t.getCreatedAt().toString(),
                "updated_at", t.getUpdatedAt().toString()
            )))
            .onErrorResume(TransferService.TransferNotFoundException.class,
                e -> ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> getByUser(ServerRequest req) {
        String userId = req.queryParam("user_id").orElse("");
        if (userId.isEmpty())
            return ServerResponse.badRequest().bodyValue(Map.of("error", "user_id required"));
        return ServerResponse.ok().body(useCase.findByUserId(userId), Object.class);
    }
}
