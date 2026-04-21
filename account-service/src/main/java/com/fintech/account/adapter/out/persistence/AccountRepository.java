package com.fintech.account.adapter.out.persistence;

import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.out.AccountPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Component
public class AccountRepository implements AccountPort {

    private final R2dbcEntityTemplate template0;
    private final R2dbcEntityTemplate template1;
    private final ShardResolver shardResolver;

    public AccountRepository(
        @Qualifier("r2dbcTemplate0") R2dbcEntityTemplate template0,
        @Qualifier("r2dbcTemplate1") R2dbcEntityTemplate template1,
        ShardResolver shardResolver
    ) {
        this.template0 = template0;
        this.template1 = template1;
        this.shardResolver = shardResolver;
    }

    private R2dbcEntityTemplate templateFor(String accountId) {
        return shardResolver.resolve(accountId) == 0 ? template0 : template1;
    }

    @Override
    public Mono<Account> save(Account account) {
        var template = templateFor(account.getId());
        Map<String, Object> row = Map.of(
            "id", account.getId(),
            "owner_id", account.getOwnerId(),
            "balance", account.getBalance().amount(),
            "created_at", account.getCreatedAt()
        );
        return template.getDatabaseClient()
            .sql("""
                INSERT INTO accounts (id, owner_id, balance, created_at)
                VALUES (:id, :owner_id, :balance, :created_at)
                ON CONFLICT (id) DO UPDATE SET balance = EXCLUDED.balance
                RETURNING id, owner_id, balance, created_at
                """)
            .bindValues(row)
            .map(r -> mapRow(r))
            .one();
    }

    @Override
    public Mono<Account> findById(String accountId) {
        return templateFor(accountId).getDatabaseClient()
            .sql("SELECT id, owner_id, balance, created_at FROM accounts WHERE id = :id")
            .bind("id", accountId)
            .map(r -> mapRow(r))
            .one();
    }

    @Override
    public Mono<Boolean> existsTransfer(String accountId, String transferId) {
        return templateFor(accountId).getDatabaseClient()
            .sql("SELECT 1 FROM transfer_idempotency WHERE account_id = :aid AND transfer_id = :tid")
            .bind("aid", accountId)
            .bind("tid", transferId)
            .map(r -> true)
            .one()
            .defaultIfEmpty(false);
    }

    @Override
    public Mono<Void> saveTransferIdempotency(String accountId, String transferId) {
        return templateFor(accountId).getDatabaseClient()
            .sql("INSERT INTO transfer_idempotency (account_id, transfer_id) VALUES (:aid, :tid) ON CONFLICT DO NOTHING")
            .bind("aid", accountId)
            .bind("tid", transferId)
            .then();
    }

    private Account mapRow(io.r2dbc.spi.Readable r) {
        return new Account(
            r.get("id", String.class),
            r.get("owner_id", String.class),
            Money.of(r.get("balance", BigDecimal.class)),
            r.get("created_at", Instant.class)
        );
    }
}
