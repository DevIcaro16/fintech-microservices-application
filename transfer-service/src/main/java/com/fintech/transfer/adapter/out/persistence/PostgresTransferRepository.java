package com.fintech.transfer.adapter.out.persistence;

import com.fintech.transfer.domain.Transfer;
import com.fintech.transfer.domain.TransferStatus;
import com.fintech.transfer.port.out.TransferRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@Component
public class PostgresTransferRepository implements TransferRepository {

    private final R2dbcEntityTemplate template;

    public PostgresTransferRepository(@Qualifier("r2dbcTemplate") R2dbcEntityTemplate template) {
        this.template = template;
    }

    @Override
    public Mono<Transfer> save(Transfer t) {
        return template.getDatabaseClient()
            .sql("""
                INSERT INTO transfers
                  (id, source_account_id, destination_account_id, amount, status, callback_url, user_id, created_at, updated_at)
                VALUES
                  (:id, :src, :dst, :amount, :status, :cbUrl, :userId, :createdAt, :updatedAt)
                ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at
                RETURNING *
                """)
            .bindValues(Map.of(
                "id", t.getId(),
                "src", t.getSourceAccountId(),
                "dst", t.getDestinationAccountId(),
                "amount", t.getAmount(),
                "status", t.getStatus().name(),
                "cbUrl", t.getCallbackUrl() != null ? t.getCallbackUrl() : "",
                "userId", t.getUserId(),
                "createdAt", t.getCreatedAt(),
                "updatedAt", t.getUpdatedAt()
            ))
            .map(r -> mapRow(r))
            .one();
    }

    @Override
    public Mono<Transfer> findById(String id) {
        return template.getDatabaseClient()
            .sql("SELECT * FROM transfers WHERE id = :id")
            .bind("id", id)
            .map(r -> mapRow(r))
            .one();
    }

    @Override
    public Mono<Transfer> updateStatus(String id, TransferStatus newStatus) {
        return template.getDatabaseClient()
            .sql("UPDATE transfers SET status = :status, updated_at = :now WHERE id = :id RETURNING *")
            .bind("status", newStatus.name())
            .bind("now", Instant.now())
            .bind("id", id)
            .map(r -> mapRow(r))
            .one();
    }

    private Transfer mapRow(io.r2dbc.spi.Readable r) {
        String cbUrl = r.get("callback_url", String.class);
        return new Transfer(
            r.get("id", String.class),
            r.get("source_account_id", String.class),
            r.get("destination_account_id", String.class),
            r.get("amount", BigDecimal.class),
            TransferStatus.valueOf(r.get("status", String.class)),
            (cbUrl == null || cbUrl.isEmpty()) ? null : cbUrl,
            r.get("user_id", String.class),
            r.get("created_at", Instant.class),
            r.get("updated_at", Instant.class)
        );
    }
}
