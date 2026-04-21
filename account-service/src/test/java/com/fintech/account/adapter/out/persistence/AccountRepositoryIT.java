package com.fintech.account.adapter.out.persistence;

import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

@Testcontainers
class AccountRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("accountdb")
        .withUsername("accountuser")
        .withPassword("accountpass")
        .withInitScript("db/migration/V1__init.sql");

    private AccountRepository repository;

    @BeforeEach
    void setup() {
        ConnectionFactory factory = new PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost())
                .port(postgres.getMappedPort(5432))
                .database("accountdb")
                .username("accountuser")
                .password("accountpass")
                .build()
        );
        R2dbcEntityTemplate template = new R2dbcEntityTemplate(factory);
        ShardResolver shardResolver = new ShardResolver() {
            @Override public int resolve(String id) { return 0; }
        };
        repository = new AccountRepository(template, template, shardResolver);
    }

    @Test
    void saveAndFindById_roundtrip() {
        Account account = Account.create("owner-it", Money.of(BigDecimal.valueOf(1000)));

        StepVerifier.create(repository.save(account).then(repository.findById(account.getId())))
            .assertNext(found -> {
                assert found.getId().equals(account.getId());
                assert found.getOwnerId().equals("owner-it");
                assert found.getBalance().amount().compareTo(BigDecimal.valueOf(1000)) == 0;
            })
            .verifyComplete();
    }

    @Test
    void save_updatesBalance_onConflict() {
        Account account = Account.create("owner-it", Money.of(BigDecimal.valueOf(500)));
        account.debit(Money.of(BigDecimal.valueOf(200)));

        StepVerifier.create(
            repository.save(Account.create("owner-it", Money.of(BigDecimal.valueOf(500))))
                .flatMap(original -> {
                    original.debit(Money.of(BigDecimal.valueOf(200)));
                    return repository.save(original);
                })
                .flatMap(updated -> repository.findById(updated.getId()))
        )
            .assertNext(found -> {
                assert found.getBalance().amount().compareTo(BigDecimal.valueOf(300)) == 0;
            })
            .verifyComplete();
    }

    @Test
    void existsTransfer_returnsFalse_whenAbsent() {
        StepVerifier.create(repository.existsTransfer("any-account", "tx-never"))
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void saveTransferIdempotency_thenExistsReturnsTrue() {
        Account account = Account.create("owner-it", Money.of(BigDecimal.valueOf(100)));

        StepVerifier.create(
            repository.save(account)
                .then(repository.saveTransferIdempotency(account.getId(), "tx-idem"))
                .then(repository.existsTransfer(account.getId(), "tx-idem"))
        )
            .expectNext(true)
            .verifyComplete();
    }
}
