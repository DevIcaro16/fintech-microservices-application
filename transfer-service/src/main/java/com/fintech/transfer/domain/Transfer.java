package com.fintech.transfer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public class Transfer {
    private final String id;
    private final String sourceAccountId;
    private final String destinationAccountId;
    private final BigDecimal amount;
    private final TransferStatus status;
    private final String callbackUrl;
    private final String userId;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Transfer(String id, String sourceAccountId, String destinationAccountId,
                    BigDecimal amount, TransferStatus status, String callbackUrl,
                    String userId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.amount = amount;
        this.status = status;
        this.callbackUrl = callbackUrl;
        this.userId = userId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Transfer create(String sourceAccountId, String destinationAccountId,
                                  BigDecimal amount, String callbackUrl) {
        Instant now = Instant.now();
        return new Transfer(
            UUID.randomUUID().toString(),
            sourceAccountId,
            destinationAccountId,
            amount,
            TransferStatus.PENDING,
            callbackUrl,
            sourceAccountId,
            now,
            now
        );
    }

    public Transfer withStatus(TransferStatus newStatus) {
        return new Transfer(id, sourceAccountId, destinationAccountId, amount,
            newStatus, callbackUrl, userId, createdAt, Instant.now());
    }

    public boolean canTransitionTo(TransferStatus target) {
        return switch (status) {
            case PENDING   -> target == TransferStatus.DEBITED || target == TransferStatus.FAILED;
            case DEBITED   -> target == TransferStatus.COMPLETED || target == TransferStatus.REVERTING;
            case REVERTING -> target == TransferStatus.FAILED;
            default        -> false;
        };
    }

    public String getId()                  { return id; }
    public String getSourceAccountId()     { return sourceAccountId; }
    public String getDestinationAccountId(){ return destinationAccountId; }
    public BigDecimal getAmount()          { return amount; }
    public TransferStatus getStatus()      { return status; }
    public String getCallbackUrl()         { return callbackUrl; }
    public String getUserId()              { return userId; }
    public Instant getCreatedAt()          { return createdAt; }
    public Instant getUpdatedAt()          { return updatedAt; }
}
