package com.fintech.account.domain;

import java.time.Instant;
import java.util.UUID;

public class Account {
    private final String id;
    private final String ownerId;
    private Money balance;
    private final Instant createdAt;

    public Account(String id, String ownerId, Money balance, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.balance = balance;
        this.createdAt = createdAt;
    }

    public static Account create(String ownerId, Money initialBalance) {
        return new Account(
            UUID.randomUUID().toString(),
            ownerId,
            initialBalance,
            Instant.now()
        );
    }

    public void debit(Money amount) {
        Money result = balance.subtract(amount);
        if (result.isNegative()) {
            throw new InsufficientFundsException(
                "Insufficient funds: balance=" + balance.amount() + ", debit=" + amount.amount());
        }
        this.balance = result;
    }

    public void credit(Money amount) {
        this.balance = balance.add(amount);
    }

    public int shard() {
        return Math.abs(id.hashCode()) % 2;
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public Money getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }

    public static class InsufficientFundsException extends RuntimeException {
        public InsufficientFundsException(String msg) { super(msg); }
    }
}
