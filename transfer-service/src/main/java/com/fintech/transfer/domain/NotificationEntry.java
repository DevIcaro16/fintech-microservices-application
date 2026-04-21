package com.fintech.transfer.domain;

import java.time.Instant;

public class NotificationEntry {
    private final String transferId;
    private final String createdAt;
    private final String callbackUrl;
    private final String payload;
    private final int attempts;
    private final String nextRetryAt;
    private final String status;

    public NotificationEntry(String transferId, String createdAt, String callbackUrl,
                             String payload, int attempts, String nextRetryAt, String status) {
        this.transferId = transferId;
        this.createdAt = createdAt;
        this.callbackUrl = callbackUrl;
        this.payload = payload;
        this.attempts = attempts;
        this.nextRetryAt = nextRetryAt;
        this.status = status;
    }

    public static NotificationEntry pending(String transferId, String callbackUrl, String payload) {
        String now = Instant.now().toString();
        return new NotificationEntry(transferId, now, callbackUrl, payload, 0, now, "PENDING");
    }

    public NotificationEntry withNextAttempt(Instant nextRetryAt) {
        return new NotificationEntry(transferId, createdAt, callbackUrl, payload,
            attempts + 1, nextRetryAt.toString(), "PENDING");
    }

    public String getTransferId()  { return transferId; }
    public String getCreatedAt()   { return createdAt; }
    public String getCallbackUrl() { return callbackUrl; }
    public String getPayload()     { return payload; }
    public int getAttempts()       { return attempts; }
    public String getNextRetryAt() { return nextRetryAt; }
    public String getStatus()      { return status; }
}
