package com.fintech.account.adapter.out.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.out.CachePort;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@Component
public class RedisCacheAdapter implements CachePort {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final String KEY_PREFIX = "account:";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisCacheAdapter(ReactiveStringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public Mono<Account> get(String accountId) {
        return redis.opsForValue().get(KEY_PREFIX + accountId)
            .flatMap(json -> Mono.fromCallable(() -> deserialize(json)))
            .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> put(Account account) {
        return Mono.fromCallable(() -> serialize(account))
            .flatMap(json -> redis.opsForValue().set(KEY_PREFIX + account.getId(), json, TTL))
            .then()
            .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> evict(String accountId) {
        return redis.delete(KEY_PREFIX + accountId).then().onErrorResume(e -> Mono.empty());
    }

    private String serialize(Account account) throws Exception {
        return mapper.writeValueAsString(Map.of(
            "id", account.getId(),
            "owner_id", account.getOwnerId(),
            "balance", account.getBalance().amount().toPlainString(),
            "created_at", account.getCreatedAt().toString()
        ));
    }

    @SuppressWarnings("unchecked")
    private Account deserialize(String json) throws Exception {
        Map<String, String> map = mapper.readValue(json, Map.class);
        return new Account(
            map.get("id"),
            map.get("owner_id"),
            Money.of(new BigDecimal(map.get("balance"))),
            Instant.parse(map.get("created_at"))
        );
    }
}
