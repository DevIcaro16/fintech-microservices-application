package com.fintech.account.adapter.out.persistence;

import org.springframework.stereotype.Component;

@Component
public class ShardResolver {
    public int resolve(String accountId) {
        return Math.abs(accountId.hashCode()) % 2;
    }
}
