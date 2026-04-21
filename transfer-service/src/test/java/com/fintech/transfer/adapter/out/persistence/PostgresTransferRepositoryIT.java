package com.fintech.transfer.adapter.out.persistence;

import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferStatus;
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
class PostgresTransferRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("transferdb")
        .withUsername("transferuser")
        .withPassword("transferpass")
        .withInitScript("db/migration/V1__init.sql");

    private PostgresTransferRepository repository;

    @BeforeEach
    void setup() {
        ConnectionFactory factory = new PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(postgres.getHost())
                .port(postgres.getMappedPort(5432))
                .database("transferdb")
                .username("transferuser")
                .password("transferpass")
                .build()
        );
        repository = new PostgresTransferRepository(new R2dbcEntityTemplate(factory));
    }

    @Test
    void saveAndFindById_roundtrip() {
        Transfer t = Transfer.create("src-1", "dst-1", BigDecimal.valueOf(250), "http://cb");

        StepVerifier.create(repository.save(t).then(repository.findById(t.getId())))
            .assertNext(found -> {
                assert found.getId().equals(t.getId());
                assert found.getStatus() == TransferStatus.PENDING;
                assert found.getAmount().compareTo(BigDecimal.valueOf(250)) == 0;
            })
            .verifyComplete();
    }

    @Test
    void updateStatus_changesStatus() {
        Transfer t = Transfer.create("src-2", "dst-2", BigDecimal.valueOf(100), null);

        StepVerifier.create(
            repository.save(t)
                .then(repository.updateStatus(t.getId(), TransferStatus.DEBITED))
                .flatMap(updated -> repository.findById(updated.getId()))
        )
            .assertNext(found -> {
                assert found.getStatus() == TransferStatus.DEBITED;
            })
            .verifyComplete();
    }
}
