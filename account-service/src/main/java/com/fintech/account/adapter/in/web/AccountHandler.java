package com.fintech.account.adapter.in.web;

import com.fintech.account.application.AccountService;
import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.in.AccountUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class AccountHandler {

    private final AccountUseCase useCase;

    public AccountHandler(AccountUseCase useCase) {
        this.useCase = useCase;
    }

    public Mono<ServerResponse> createAccount(ServerRequest req) {
        return req.bodyToMono(Map.class)
            .flatMap(body -> {
                String ownerId = (String) body.get("owner_id");
                BigDecimal initial = new BigDecimal(body.getOrDefault("initial_balance", "0").toString());
                return useCase.createAccount(ownerId, Money.of(initial));
            })
            .flatMap(account -> ServerResponse.status(HttpStatus.CREATED).bodyValue(Map.of(
                "id", account.getId(),
                "owner_id", account.getOwnerId(),
                "balance", account.getBalance().amount(),
                "shard", account.shard()
            )))
            .onErrorResume(e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())));
    }

    public Mono<ServerResponse> getAccount(ServerRequest req) {
        return useCase.findById(req.pathVariable("id"))
            .flatMap(account -> ServerResponse.ok().bodyValue(Map.of(
                "id", account.getId(),
                "owner_id", account.getOwnerId(),
                "balance", account.getBalance().amount()
            )))
            .onErrorResume(AccountService.AccountNotFoundException.class,
                e -> ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> getBalance(ServerRequest req) {
        return useCase.getBalance(req.pathVariable("id"))
            .flatMap(money -> ServerResponse.ok().bodyValue(Map.of(
                "account_id", req.pathVariable("id"),
                "balance", money.amount()
            )))
            .onErrorResume(AccountService.AccountNotFoundException.class,
                e -> ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> debit(ServerRequest req) {
        return req.bodyToMono(Map.class)
            .flatMap(body -> {
                BigDecimal amount = new BigDecimal(body.get("amount").toString());
                String transferId = (String) body.get("transfer_id");
                return useCase.debit(req.pathVariable("id"), Money.of(amount), transferId);
            })
            .flatMap(account -> ServerResponse.ok().bodyValue(Map.of(
                "account_id", account.getId(),
                "new_balance", account.getBalance().amount()
            )))
            .onErrorResume(AccountService.AccountNotFoundException.class,
                e -> ServerResponse.notFound().build())
            .onErrorResume(Account.InsufficientFundsException.class,
                e -> ServerResponse.badRequest().bodyValue(Map.of("error", "insufficient funds")))
            .onErrorResume(AccountService.DuplicateTransferException.class,
                e -> ServerResponse.status(HttpStatus.CONFLICT).bodyValue(Map.of("error", "duplicate transfer_id")));
    }

    public Mono<ServerResponse> credit(ServerRequest req) {
        return req.bodyToMono(Map.class)
            .flatMap(body -> {
                BigDecimal amount = new BigDecimal(body.get("amount").toString());
                String transferId = (String) body.get("transfer_id");
                return useCase.credit(req.pathVariable("id"), Money.of(amount), transferId);
            })
            .flatMap(account -> ServerResponse.ok().bodyValue(Map.of(
                "account_id", account.getId(),
                "new_balance", account.getBalance().amount()
            )))
            .onErrorResume(AccountService.AccountNotFoundException.class,
                e -> ServerResponse.notFound().build());
    }
}
