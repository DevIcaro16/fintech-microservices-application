package com.fintech.account.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record Money(BigDecimal amount) {
    public Money {
        if (amount == null) throw new IllegalArgumentException("amount cannot be null");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(BigDecimal value) { return new Money(value); }
    public static Money zero() { return new Money(BigDecimal.ZERO); }

    public Money add(Money other) { return new Money(this.amount.add(other.amount)); }
    public Money subtract(Money other) { return new Money(this.amount.subtract(other.amount)); }

    public boolean isNegative() { return amount.compareTo(BigDecimal.ZERO) < 0; }
    public boolean isZeroOrPositive() { return amount.compareTo(BigDecimal.ZERO) >= 0; }
}
