# Account Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar o `account-service` em Java 21 + Spring WebFlux com arquitetura Hexagonal, R2DBC reativo para dois shards PostgreSQL, cache de saldo no Redis Cluster e consumo/publicação de eventos Kafka para o Saga de transferência.

**Architecture:** Hexagonal (Ports & Adapters): o domínio (`Account`, `Money`) não conhece frameworks. As portas de entrada são `AccountUseCase` (interface). Os adaptadores de entrada são os handlers WebFlux. Os adaptadores de saída são `AccountRepository` (R2DBC, shard-aware) e `EventPublisher` (Outbox pattern). O shard resolver decide qual dos dois PostgreSQL usar baseado em `account_id % 2`. Todo o pipeline é reativo: `Mono<T>` e `Flux<T>` do início ao fim, sem nenhuma chamada bloqueante.

**Tech Stack:** Java 21, Spring Boot 3.3, Spring WebFlux, Spring Data R2DBC, io.r2dbc:r2dbc-postgresql, Spring Kafka, Micrometer + Prometheus, PostgreSQL 16 (2 shards), Redis Cluster (já provisionado), Gradle, Docker, Kubernetes (namespace `fintech`).

---

## Planos desta série

- [01] Infra Foundation ✅
- [02] auth-service (Go) ✅
- [03] api-gateway (Bun + Elysia) ✅
- **[04] account-service (Java + Spring WebFlux)** ← você está aqui
- [05] transfer-service (Go)
- [06] notification-service (Bun + Elysia)
- [07] Load tests (k6)

---

## Estrutura de arquivos

```
account-service/
├── src/main/java/com/fintech/account/
│   ├── domain/
│   │   ├── Account.java              # entidade de domínio pura
│   │   └── Money.java                # value object imutável
│   ├── port/
│   │   ├── in/
│   │   │   └── AccountUseCase.java   # porta de entrada (interface)
│   │   └── out/
│   │       ├── AccountPort.java      # porta de saída para persistence
│   │       └── EventPort.java        # porta de saída para eventos
│   ├── application/
│   │   └── AccountService.java       # implementa AccountUseCase
│   ├── adapter/
│   │   ├── in/web/
│   │   │   └── AccountHandler.java   # handlers WebFlux (RouterFunction)
│   │   └── out/
│   │       ├── persistence/
│   │       │   ├── AccountRepository.java    # R2DBC, shard-aware
│   │       │   ├── ShardResolver.java        # account_id % 2 → shard
│   │       │   └── OutboxRepository.java     # outbox table via R2DBC
│   │       └── cache/
│   │           └── BalanceCacheAdapter.java  # Redis cache de saldo
│   └── config/
│       ├── RouterConfig.java         # define as rotas WebFlux
│       ├── R2dbcConfig.java          # configura dois ConnectionFactories
│       └── KafkaConfig.java          # producer + consumer configs
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/
│       ├── V1__create_accounts.sql   # tabela accounts + outbox (shard-0)
│       └── V1__create_accounts.sql   # (mesmo arquivo para shard-1)
├── src/test/java/com/fintech/account/
│   └── application/
│       └── AccountServiceTest.java   # testes unitários do service
├── build.gradle
├── settings.gradle
├── Dockerfile
└── k8s/
    ├── deployment.yaml
    ├── service.yaml
    ├── configmap.yaml
    └── postgres-shards.yaml          # 2 StatefulSets PostgreSQL
```

---

## Contratos de API

```
POST /accounts
  Body: { "owner_id": "uuid", "initial_balance": 0.00 }
  201:  { "id": "uuid", "owner_id": "uuid", "balance": 0.00, "shard": 0|1 }

GET /accounts/{id}
  200:  { "id": "uuid", "owner_id": "uuid", "balance": 0.00 }
  404:  { "error": "account not found" }

GET /accounts/{id}/balance
  200:  { "account_id": "uuid", "balance": 0.00, "cached": true|false }

POST /accounts/{id}/debit
  Body: { "amount": 100.00, "transfer_id": "uuid" }
  200:  { "account_id": "uuid", "new_balance": 0.00 }
  400:  { "error": "insufficient funds" }
  409:  { "error": "duplicate transfer_id" }

POST /accounts/{id}/credit
  Body: { "amount": 100.00, "transfer_id": "uuid" }
  200:  { "account_id": "uuid", "new_balance": 0.00 }

GET /healthz  → 200 OK
GET /metrics  → Prometheus metrics
```

---

## Kafka: eventos consumidos e publicados

| Tópico consumido | Ação |
|---|---|
| `transfer.requested` | executa débito na conta origem |
| `debit.reversal` | estorna débito (compensação Saga) |

| Tópico publicado | Quando |
|---|---|
| `debit.completed` | débito executado com sucesso |
| `debit.failed` | saldo insuficiente ou erro |
| `credit.completed` | crédito executado com sucesso |
| `credit.failed` | erro no crédito |

---

## Task 1: Scaffold do projeto com Gradle

**Files:**
- Create: `account-service/settings.gradle`
- Create: `account-service/build.gradle`
- Create: `account-service/src/main/resources/application.yml`

- [ ] **Step 1: Criar settings.gradle**

```groovy
// account-service/settings.gradle
rootProject.name = 'account-service'
```

- [ ] **Step 2: Criar build.gradle**

```groovy
// account-service/build.gradle
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.0'
    id 'io.spring.dependency-management' version '1.1.5'
}

group = 'com.fintech'
version = '1.0.0'
sourceCompatibility = '21'

repositories {
    mavenCentral()
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webflux'
    implementation 'org.springframework.boot:spring-boot-starter-data-r2dbc'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'io.r2dbc:r2dbc-postgresql:1.0.5.RELEASE'
    implementation 'org.springframework.data:spring-data-redis'
    implementation 'io.lettuce:lettuce-core'
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'com.fasterxml.jackson.core:jackson-databind'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'io.projectreactor:reactor-test'
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Criar application.yml**

```yaml
# account-service/src/main/resources/application.yml
server:
  port: 8080

spring:
  application:
    name: account-service
  r2dbc:
    shard0:
      url: ${SHARD0_URL:r2dbc:postgresql://accountuser:accountpass@account-postgres-shard0:5432/accountdb}
    shard1:
      url: ${SHARD1_URL:r2dbc:postgresql://accountuser:accountpass@account-postgres-shard1:5432/accountdb}
  data:
    redis:
      cluster:
        nodes: ${REDIS_CLUSTER_NODES:redis-cluster-0.redis-cluster.data.svc.cluster.local:6379,redis-cluster-1.redis-cluster.data.svc.cluster.local:6379,redis-cluster-2.redis-cluster.data.svc.cluster.local:6379}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:fintech-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092}
    consumer:
      group-id: account-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    export:
      prometheus:
        enabled: true

logging:
  pattern:
    console: '{"ts":"%d{ISO8601}","level":"%p","service":"account-service","trace_id":"%X{traceId}","msg":"%m"}%n'
```

- [ ] **Step 4: Criar main class**

```bash
mkdir -p account-service/src/main/java/com/fintech/account/{domain,port/in,port/out,application,adapter/in/web,adapter/out/persistence,adapter/out/cache,config}
mkdir -p account-service/src/test/java/com/fintech/account/application
mkdir -p account-service/src/main/resources/db/migration
```

```java
// account-service/src/main/java/com/fintech/account/AccountServiceApplication.java
package com.fintech.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
```

- [ ] **Step 5: Verificar build**

```bash
cd account-service && ./gradlew build -x test 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add account-service/
git commit -m "feat(account): initialize Spring WebFlux project with Gradle"
```

---

## Task 2: Domínio e Ports (Hexagonal core)

**Files:**
- Create: `account-service/src/main/java/com/fintech/account/domain/Account.java`
- Create: `account-service/src/main/java/com/fintech/account/domain/Money.java`
- Create: `account-service/src/main/java/com/fintech/account/port/in/AccountUseCase.java`
- Create: `account-service/src/main/java/com/fintech/account/port/out/AccountPort.java`
- Create: `account-service/src/main/java/com/fintech/account/port/out/EventPort.java`

- [ ] **Step 1: Criar Money.java (value object)**

```java
// domain/Money.java
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
```

- [ ] **Step 2: Criar Account.java**

```java
// domain/Account.java
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
            throw new InsufficientFundsException("Insufficient funds: balance=" + balance.amount() + ", debit=" + amount.amount());
        }
        this.balance = result;
    }

    public void credit(Money amount) {
        this.balance = balance.add(amount);
    }

    public int shard() {
        // shard resolver: account_id hash % 2
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
```

- [ ] **Step 3: Criar AccountUseCase.java (porta de entrada)**

```java
// port/in/AccountUseCase.java
package com.fintech.account.port.in;

import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import reactor.core.publisher.Mono;

public interface AccountUseCase {
    Mono<Account> createAccount(String ownerId, Money initialBalance);
    Mono<Account> findById(String accountId);
    Mono<Money> getBalance(String accountId);
    Mono<Account> debit(String accountId, Money amount, String transferId);
    Mono<Account> credit(String accountId, Money amount, String transferId);
}
```

- [ ] **Step 4: Criar AccountPort.java e EventPort.java (portas de saída)**

```java
// port/out/AccountPort.java
package com.fintech.account.port.out;

import com.fintech.account.domain.Account;
import reactor.core.publisher.Mono;

public interface AccountPort {
    Mono<Account> save(Account account);
    Mono<Account> findById(String accountId);
    Mono<Boolean> existsTransfer(String accountId, String transferId);
    Mono<Void> saveTransferIdempotency(String accountId, String transferId);
}
```

```java
// port/out/EventPort.java
package com.fintech.account.port.out;

import reactor.core.publisher.Mono;

public interface EventPort {
    Mono<Void> publish(String topic, String key, String payload);
}
```

- [ ] **Step 5: Commit**

```bash
git add account-service/src/main/java/com/fintech/account/domain/ \
        account-service/src/main/java/com/fintech/account/port/
git commit -m "feat(account): add hexagonal domain (Account, Money) and ports"
```

---

## Task 3: Application Service (use case implementation)

**Files:**
- Create: `account-service/src/main/java/com/fintech/account/application/AccountService.java`

- [ ] **Step 1: Criar AccountService.java**

```java
// application/AccountService.java
package com.fintech.account.application;

import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.in.AccountUseCase;
import com.fintech.account.port.out.AccountPort;
import com.fintech.account.port.out.EventPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class AccountService implements AccountUseCase {

    private final AccountPort accountPort;
    private final EventPort eventPort;
    private final ObjectMapper mapper;

    public AccountService(AccountPort accountPort, EventPort eventPort, ObjectMapper mapper) {
        this.accountPort = accountPort;
        this.eventPort = eventPort;
        this.mapper = mapper;
    }

    @Override
    public Mono<Account> createAccount(String ownerId, Money initialBalance) {
        Account account = Account.create(ownerId, initialBalance);
        return accountPort.save(account);
    }

    @Override
    public Mono<Account> findById(String accountId) {
        return accountPort.findById(accountId)
            .switchIfEmpty(Mono.error(new AccountNotFoundException(accountId)));
    }

    @Override
    public Mono<Money> getBalance(String accountId) {
        return findById(accountId).map(Account::getBalance);
    }

    @Override
    public Mono<Account> debit(String accountId, Money amount, String transferId) {
        return accountPort.existsTransfer(accountId, transferId)
            .flatMap(exists -> {
                if (exists) return Mono.error(new DuplicateTransferException(transferId));
                return accountPort.findById(accountId)
                    .switchIfEmpty(Mono.error(new AccountNotFoundException(accountId)))
                    .flatMap(account -> {
                        try {
                            account.debit(amount);
                        } catch (Account.InsufficientFundsException e) {
                            return publishEvent("debit.failed", accountId,
                                Map.of("account_id", accountId, "transfer_id", transferId, "reason", "insufficient_funds"))
                                .then(Mono.error(e));
                        }
                        return accountPort.save(account)
                            .flatMap(saved ->
                                accountPort.saveTransferIdempotency(accountId, transferId)
                                    .then(publishEvent("debit.completed", accountId,
                                        Map.of("account_id", accountId, "transfer_id", transferId,
                                               "new_balance", saved.getBalance().amount())))
                                    .thenReturn(saved)
                            );
                    });
            });
    }

    @Override
    public Mono<Account> credit(String accountId, Money amount, String transferId) {
        return accountPort.existsTransfer(accountId, transferId)
            .flatMap(exists -> {
                if (exists) return Mono.error(new DuplicateTransferException(transferId));
                return accountPort.findById(accountId)
                    .switchIfEmpty(Mono.error(new AccountNotFoundException(accountId)))
                    .flatMap(account -> {
                        account.credit(amount);
                        return accountPort.save(account)
                            .flatMap(saved ->
                                accountPort.saveTransferIdempotency(accountId, transferId)
                                    .then(publishEvent("credit.completed", accountId,
                                        Map.of("account_id", accountId, "transfer_id", transferId,
                                               "new_balance", saved.getBalance().amount())))
                                    .thenReturn(saved)
                            );
                    });
            });
    }

    private Mono<Void> publishEvent(String topic, String key, Map<String, Object> payload) {
        try {
            return eventPort.publish(topic, key, mapper.writeValueAsString(payload));
        } catch (Exception e) {
            return Mono.error(e);
        }
    }

    public static class AccountNotFoundException extends RuntimeException {
        public AccountNotFoundException(String id) { super("Account not found: " + id); }
    }

    public static class DuplicateTransferException extends RuntimeException {
        public DuplicateTransferException(String transferId) { super("Duplicate transfer: " + transferId); }
    }
}
```

- [ ] **Step 2: Escrever testes unitários**

```java
// src/test/java/com/fintech/account/application/AccountServiceTest.java
package com.fintech.account.application;

import com.fintech.account.domain.Money;
import com.fintech.account.port.out.AccountPort;
import com.fintech.account.port.out.EventPort;
import com.fintech.account.domain.Account;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {

    private final AccountPort accountPort = mock(AccountPort.class);
    private final EventPort eventPort = mock(EventPort.class);
    private final AccountService service = new AccountService(accountPort, eventPort, new ObjectMapper());

    @Test
    void debit_shouldReduceBalance_whenFundsAreAvailable() {
        Account account = Account.create("owner-1", Money.of(BigDecimal.valueOf(200)));
        when(accountPort.existsTransfer(anyString(), anyString())).thenReturn(Mono.just(false));
        when(accountPort.findById(account.getId())).thenReturn(Mono.just(account));
        when(accountPort.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(accountPort.saveTransferIdempotency(anyString(), anyString())).thenReturn(Mono.empty());
        when(eventPort.publish(anyString(), anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.debit(account.getId(), Money.of(BigDecimal.valueOf(50)), "tx-1"))
            .assertNext(a -> {
                assert a.getBalance().amount().compareTo(BigDecimal.valueOf(150)) == 0;
            })
            .verifyComplete();
    }

    @Test
    void debit_shouldFail_whenInsufficientFunds() {
        Account account = Account.create("owner-2", Money.of(BigDecimal.valueOf(10)));
        when(accountPort.existsTransfer(anyString(), anyString())).thenReturn(Mono.just(false));
        when(accountPort.findById(account.getId())).thenReturn(Mono.just(account));
        when(eventPort.publish(eq("debit.failed"), anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.debit(account.getId(), Money.of(BigDecimal.valueOf(100)), "tx-2"))
            .expectError(Account.InsufficientFundsException.class)
            .verify();
    }

    @Test
    void credit_shouldIncreaseBalance() {
        Account account = Account.create("owner-3", Money.of(BigDecimal.valueOf(100)));
        when(accountPort.existsTransfer(anyString(), anyString())).thenReturn(Mono.just(false));
        when(accountPort.findById(account.getId())).thenReturn(Mono.just(account));
        when(accountPort.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(accountPort.saveTransferIdempotency(anyString(), anyString())).thenReturn(Mono.empty());
        when(eventPort.publish(anyString(), anyString(), anyString())).thenReturn(Mono.empty());

        StepVerifier.create(service.credit(account.getId(), Money.of(BigDecimal.valueOf(50)), "tx-3"))
            .assertNext(a -> {
                assert a.getBalance().amount().compareTo(BigDecimal.valueOf(150)) == 0;
            })
            .verifyComplete();
    }
}
```

- [ ] **Step 3: Rodar testes**

```bash
cd account-service && ./gradlew test 2>&1 | tail -10
```

Expected: `3 tests completed, 0 failures`

- [ ] **Step 4: Commit**

```bash
git add account-service/src/main/java/com/fintech/account/application/ \
        account-service/src/test/
git commit -m "feat(account): add AccountService (use case) with unit tests"
```

---

## Task 4: Adaptadores de persistência (R2DBC + Shard Resolver)

**Files:**
- Create: `account-service/src/main/java/com/fintech/account/adapter/out/persistence/ShardResolver.java`
- Create: `account-service/src/main/java/com/fintech/account/adapter/out/persistence/AccountRepository.java`
- Create: `account-service/src/main/java/com/fintech/account/config/R2dbcConfig.java`
- Create: `account-service/src/main/resources/db/migration/V1__init.sql`

- [ ] **Step 1: Criar migração SQL (mesma para os dois shards)**

```sql
-- account-service/src/main/resources/db/migration/V1__init.sql
CREATE TABLE IF NOT EXISTS accounts (
    id            VARCHAR(36) PRIMARY KEY,
    owner_id      VARCHAR(36) NOT NULL,
    balance       NUMERIC(19, 2) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transfer_idempotency (
    account_id   VARCHAR(36) NOT NULL,
    transfer_id  VARCHAR(36) NOT NULL,
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (account_id, transfer_id)
);

CREATE TABLE IF NOT EXISTS outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR NOT NULL,
    event_type   VARCHAR NOT NULL,
    payload      TEXT NOT NULL,
    published    BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_unpublished ON outbox(published) WHERE published = FALSE;
```

- [ ] **Step 2: Criar ShardResolver.java**

```java
// adapter/out/persistence/ShardResolver.java
package com.fintech.account.adapter.out.persistence;

import org.springframework.stereotype.Component;

@Component
public class ShardResolver {
    public int resolve(String accountId) {
        return Math.abs(accountId.hashCode()) % 2;
    }
}
```

- [ ] **Step 3: Criar R2dbcConfig.java (dois ConnectionFactory)**

```java
// config/R2dbcConfig.java
package com.fintech.account.config;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;

import java.net.URI;

@Configuration
public class R2dbcConfig {

    @Value("${spring.r2dbc.shard0.url}")
    private String shard0Url;

    @Value("${spring.r2dbc.shard1.url}")
    private String shard1Url;

    @Bean("connectionFactory0")
    public ConnectionFactory connectionFactory0() {
        return buildFactory(shard0Url);
    }

    @Bean("connectionFactory1")
    public ConnectionFactory connectionFactory1() {
        return buildFactory(shard1Url);
    }

    @Bean("r2dbcTemplate0")
    public R2dbcEntityTemplate r2dbcTemplate0() {
        return new R2dbcEntityTemplate(connectionFactory0());
    }

    @Bean("r2dbcTemplate1")
    public R2dbcEntityTemplate r2dbcTemplate1() {
        return new R2dbcEntityTemplate(connectionFactory1());
    }

    private ConnectionFactory buildFactory(String r2dbcUrl) {
        // Parse r2dbc:postgresql://user:pass@host:port/db
        URI uri = URI.create(r2dbcUrl.replace("r2dbc:", ""));
        String[] userInfo = uri.getUserInfo().split(":");
        return new PostgresqlConnectionFactory(
            PostgresqlConnectionConfiguration.builder()
                .host(uri.getHost())
                .port(uri.getPort())
                .database(uri.getPath().substring(1))
                .username(userInfo[0])
                .password(userInfo[1])
                .build()
        );
    }
}
```

- [ ] **Step 4: Criar AccountRepository.java (shard-aware)**

```java
// adapter/out/persistence/AccountRepository.java
package com.fintech.account.adapter.out.persistence;

import com.fintech.account.domain.Account;
import com.fintech.account.domain.Money;
import com.fintech.account.port.out.AccountPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
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
        var row = Map.of(
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

    private Account mapRow(io.r2dbc.spi.Row r) {
        return new Account(
            r.get("id", String.class),
            r.get("owner_id", String.class),
            Money.of(r.get("balance", BigDecimal.class)),
            r.get("created_at", Instant.class)
        );
    }
}
```

- [ ] **Step 5: Verificar compilação**

```bash
cd account-service && ./gradlew compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add account-service/src/main/java/com/fintech/account/adapter/out/persistence/ \
        account-service/src/main/java/com/fintech/account/config/R2dbcConfig.java \
        account-service/src/main/resources/db/migration/
git commit -m "feat(account): add R2DBC shard-aware repository and ShardResolver"
```

---

## Task 5: Adaptadores Web, Cache, Kafka e Config

**Files:**
- Create: `account-service/src/main/java/com/fintech/account/adapter/in/web/AccountHandler.java`
- Create: `account-service/src/main/java/com/fintech/account/adapter/out/cache/BalanceCacheAdapter.java`
- Create: `account-service/src/main/java/com/fintech/account/adapter/out/persistence/KafkaEventAdapter.java`
- Create: `account-service/src/main/java/com/fintech/account/config/RouterConfig.java`
- Create: `account-service/src/main/java/com/fintech/account/config/KafkaConfig.java`

- [ ] **Step 1: Criar AccountHandler.java**

```java
// adapter/in/web/AccountHandler.java
package com.fintech.account.adapter.in.web;

import com.fintech.account.application.AccountService;
import com.fintech.account.domain.Money;
import com.fintech.account.port.in.AccountUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Map;

@Component
public class AccountHandler {

    private final AccountUseCase useCase;

    public AccountHandler(AccountUseCase useCase) {
        this.useCase = useCase;
    }

    public Mono<ServerResponse> createAccount(ServerRequest req) {
        return req.bodyToMono(Map.class)
            .flatMap(body -> {
                String ownerId = (String) body.get("owner_id");
                BigDecimal initial = new BigDecimal(body.getOrDefault("initial_balance", "0").toString());
                return useCase.createAccount(ownerId, Money.of(initial));
            })
            .flatMap(account -> ServerResponse.status(HttpStatus.CREATED).bodyValue(Map.of(
                "id", account.getId(),
                "owner_id", account.getOwnerId(),
                "balance", account.getBalance().amount(),
                "shard", account.shard()
            )))
            .onErrorResume(e -> ServerResponse.badRequest().bodyValue(Map.of("error", e.getMessage())));
    }

    public Mono<ServerResponse> getAccount(ServerRequest req) {
        return useCase.findById(req.pathVariable("id"))
            .flatMap(account -> ServerResponse.ok().bodyValue(Map.of(
                "id", account.getId(),
                "owner_id", account.getOwnerId(),
                "balance", account.getBalance().amount()
            )))
            .onErrorResume(AccountService.AccountNotFoundException.class,
                e -> ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> getBalance(ServerRequest req) {
        return useCase.getBalance(req.pathVariable("id"))
            .flatMap(money -> ServerResponse.ok().bodyValue(Map.of(
                "account_id", req.pathVariable("id"),
                "balance", money.amount()
            )))
            .onErrorResume(AccountService.AccountNotFoundException.class,
                e -> ServerResponse.notFound().build());
    }

    public Mono<ServerResponse> debit(ServerRequest req) {
        return req.bodyToMono(Map.class)
            .flatMap(body -> {
                BigDecimal amount = new BigDecimal(body.get("amount").toString());
                String transferId = (String) body.get("transfer_id");
                return useCase.debit(req.pathVariable("id"), Money.of(amount), transferId);
            })
            .flatMap(account -> ServerResponse.ok().bodyValue(Map.of(
                "account_id", account.getId(),
                "new_balance", account.getBalance().amount()
            )))
            .onErrorResume(AccountService.AccountNotFoundException.class,
                e -> ServerResponse.notFound().build())
            .onErrorResume(Account.InsufficientFundsException.class,
                e -> ServerResponse.badRequest().bodyValue(Map.of("error", "insufficient funds")))
            .onErrorResume(AccountService.DuplicateTransferException.class,
                e -> ServerResponse.status(HttpStatus.CONFLICT).bodyValue(Map.of("error", "duplicate transfer_id")));
    }

    public Mono<ServerResponse> credit(ServerRequest req) {
        return req.bodyToMono(Map.class)
            .flatMap(body -> {
                BigDecimal amount = new BigDecimal(body.get("amount").toString());
                String transferId = (String) body.get("transfer_id");
                return useCase.credit(req.pathVariable("id"), Money.of(amount), transferId);
            })
            .flatMap(account -> ServerResponse.ok().bodyValue(Map.of(
                "account_id", account.getId(),
                "new_balance", account.getBalance().amount()
            )))
            .onErrorResume(AccountService.AccountNotFoundException.class,
                e -> ServerResponse.notFound().build());
    }
}
```

Adicione o import faltante: `import com.fintech.account.domain.Account;`

- [ ] **Step 2: Criar RouterConfig.java**

```java
// config/RouterConfig.java
package com.fintech.account.config;

import com.fintech.account.adapter.in.web.AccountHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration
public class RouterConfig {

    @Bean
    public RouterFunction<ServerResponse> routes(AccountHandler handler) {
        return RouterFunctions.route()
            .GET("/healthz", req -> ServerResponse.ok().bodyValue(Map.of("status", "ok")))
            .POST("/accounts", handler::createAccount)
            .GET("/accounts/{id}", handler::getAccount)
            .GET("/accounts/{id}/balance", handler::getBalance)
            .POST("/accounts/{id}/debit", handler::debit)
            .POST("/accounts/{id}/credit", handler::credit)
            .build();
    }
}
```

Adicione o import: `import java.util.Map;`

- [ ] **Step 3: Criar KafkaEventAdapter.java**

```java
// adapter/out/persistence/KafkaEventAdapter.java
package com.fintech.account.adapter.out.persistence;

import com.fintech.account.port.out.EventPort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class KafkaEventAdapter implements EventPort {

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaEventAdapter(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public Mono<Void> publish(String topic, String key, String payload) {
        return Mono.fromFuture(kafkaTemplate.send(topic, key, payload).toCompletableFuture())
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }
}
```

- [ ] **Step 4: Criar KafkaConfig.java**

```java
// config/KafkaConfig.java
package com.fintech.account.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaProperties props) {
        Map<String, Object> config = props.buildProducerProperties(null);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> factory) {
        return new KafkaTemplate<>(factory);
    }
}
```

- [ ] **Step 5: Verificar compilação**

```bash
cd account-service && ./gradlew compileJava 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add account-service/src/main/java/com/fintech/account/adapter/ \
        account-service/src/main/java/com/fintech/account/config/
git commit -m "feat(account): add web handler, router, Kafka adapter and cache stub"
```

---

## Task 6: PostgreSQL shards + K8s + Dockerfile + Deploy

**Files:**
- Create: `account-service/k8s/postgres-shards.yaml`
- Create: `account-service/k8s/configmap.yaml`
- Create: `account-service/k8s/deployment.yaml`
- Create: `account-service/k8s/service.yaml`
- Create: `account-service/Dockerfile`

- [ ] **Step 1: Criar postgres-shards.yaml (2 StatefulSets)**

```yaml
# account-service/k8s/postgres-shards.yaml
apiVersion: v1
kind: Secret
metadata:
  name: account-postgres-secret
  namespace: fintech
type: Opaque
stringData:
  username: accountuser
  password: accountpass
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: account-migrations
  namespace: fintech
data:
  V1__init.sql: |
    CREATE TABLE IF NOT EXISTS accounts (
        id VARCHAR(36) PRIMARY KEY,
        owner_id VARCHAR(36) NOT NULL,
        balance NUMERIC(19,2) NOT NULL DEFAULT 0,
        created_at TIMESTAMPTZ DEFAULT NOW()
    );
    CREATE TABLE IF NOT EXISTS transfer_idempotency (
        account_id VARCHAR(36) NOT NULL,
        transfer_id VARCHAR(36) NOT NULL,
        created_at TIMESTAMPTZ DEFAULT NOW(),
        PRIMARY KEY (account_id, transfer_id)
    );
    CREATE TABLE IF NOT EXISTS outbox (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        aggregate_id VARCHAR NOT NULL,
        event_type VARCHAR NOT NULL,
        payload TEXT NOT NULL,
        published BOOLEAN DEFAULT FALSE,
        created_at TIMESTAMPTZ DEFAULT NOW()
    );
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: account-postgres-shard0
  namespace: fintech
spec:
  serviceName: account-postgres-shard0
  replicas: 1
  selector:
    matchLabels:
      app: account-postgres-shard0
  template:
    metadata:
      labels:
        app: account-postgres-shard0
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          env:
            - name: POSTGRES_DB
              value: accountdb
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: account-postgres-secret
                  key: username
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: account-postgres-secret
                  key: password
          ports:
            - containerPort: 5432
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
            - name: migrations
              mountPath: /docker-entrypoint-initdb.d
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
      volumes:
        - name: data
          emptyDir: {}
        - name: migrations
          configMap:
            name: account-migrations
---
apiVersion: v1
kind: Service
metadata:
  name: account-postgres-shard0
  namespace: fintech
spec:
  clusterIP: None
  selector:
    app: account-postgres-shard0
  ports:
    - port: 5432
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: account-postgres-shard1
  namespace: fintech
spec:
  serviceName: account-postgres-shard1
  replicas: 1
  selector:
    matchLabels:
      app: account-postgres-shard1
  template:
    metadata:
      labels:
        app: account-postgres-shard1
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          env:
            - name: POSTGRES_DB
              value: accountdb
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: account-postgres-secret
                  key: username
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: account-postgres-secret
                  key: password
          ports:
            - containerPort: 5432
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
            - name: migrations
              mountPath: /docker-entrypoint-initdb.d
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
      volumes:
        - name: data
          emptyDir: {}
        - name: migrations
          configMap:
            name: account-migrations
---
apiVersion: v1
kind: Service
metadata:
  name: account-postgres-shard1
  namespace: fintech
spec:
  clusterIP: None
  selector:
    app: account-postgres-shard1
  ports:
    - port: 5432
```

- [ ] **Step 2: Criar configmap.yaml**

```yaml
# account-service/k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: account-service-config
  namespace: fintech
data:
  SHARD0_URL: "r2dbc:postgresql://accountuser:accountpass@account-postgres-shard0:5432/accountdb"
  SHARD1_URL: "r2dbc:postgresql://accountuser:accountpass@account-postgres-shard1:5432/accountdb"
  REDIS_CLUSTER_NODES: "redis-cluster-0.redis-cluster.data.svc.cluster.local:6379,redis-cluster-1.redis-cluster.data.svc.cluster.local:6379,redis-cluster-2.redis-cluster.data.svc.cluster.local:6379"
  KAFKA_BOOTSTRAP: "fintech-kafka-kafka-bootstrap.kafka.svc.cluster.local:9092"
```

- [ ] **Step 3: Criar deployment.yaml**

```yaml
# account-service/k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: account-service
  namespace: fintech
  labels:
    app: account-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: account-service
  template:
    metadata:
      labels:
        app: account-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      containers:
        - name: account-service
          image: account-service:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: account-service-config
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 10
          resources:
            requests:
              cpu: 200m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
```

- [ ] **Step 4: Criar service.yaml**

```yaml
# account-service/k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: account-service
  namespace: fintech
  labels:
    app: account-service
spec:
  selector:
    app: account-service
  ports:
    - name: http
      port: 80
      targetPort: 8080
  type: ClusterIP
```

- [ ] **Step 5: Criar Dockerfile**

```dockerfile
# account-service/Dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew build -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 6: Build + Deploy**

```bash
eval $(minikube docker-env)
cd account-service
docker build -t account-service:latest .

kubectl apply -f k8s/postgres-shards.yaml
kubectl apply -f k8s/configmap.yaml k8s/deployment.yaml k8s/service.yaml
kubectl wait deployment account-service -n fintech --for=condition=available --timeout=120s
```

- [ ] **Step 7: Commit**

```bash
git add account-service/
git commit -m "feat(account): add Dockerfile, K8s manifests and PostgreSQL shards"
```

---

## Task 7: Validação end-to-end

- [ ] **Step 1: Port-forward**

```bash
kubectl port-forward svc/account-service 8081:80 -n fintech &
```

- [ ] **Step 2: Criar conta (shard determinístico)**

```bash
curl -s -X POST http://localhost:8081/accounts \
  -H "Content-Type: application/json" \
  -d '{"owner_id":"user-001","initial_balance":500.00}' | python3 -m json.tool
```

Expected: `{"id":"...","owner_id":"user-001","balance":500.00,"shard":0|1}`

- [ ] **Step 3: Consultar saldo**

```bash
ACCOUNT_ID=<id do step anterior>
curl -s http://localhost:8081/accounts/$ACCOUNT_ID/balance | python3 -m json.tool
```

- [ ] **Step 4: Débito**

```bash
curl -s -X POST http://localhost:8081/accounts/$ACCOUNT_ID/debit \
  -H "Content-Type: application/json" \
  -d '{"amount":100.00,"transfer_id":"tx-test-001"}' | python3 -m json.tool
```

Expected: `{"account_id":"...","new_balance":400.00}`

- [ ] **Step 5: Débito idempotente (deve retornar 409)**

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -X POST http://localhost:8081/accounts/$ACCOUNT_ID/debit \
  -H "Content-Type: application/json" \
  -d '{"amount":100.00,"transfer_id":"tx-test-001"}'
```

Expected: `409`

- [ ] **Step 6: Débito com saldo insuficiente (deve retornar 400)**

```bash
curl -s -X POST http://localhost:8081/accounts/$ACCOUNT_ID/debit \
  -H "Content-Type: application/json" \
  -d '{"amount":9999.00,"transfer_id":"tx-test-002"}' | python3 -m json.tool
```

Expected: `{"error":"insufficient funds"}`

- [ ] **Step 7: Verificar evento Kafka publicado**

```bash
kubectl exec -n kafka fintech-kafka-controller-0 -- bash -c \
  "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
   --topic debit.completed --from-beginning --max-messages 1 --timeout-ms 5000 2>/dev/null"
```

Expected: JSON com `account_id`, `transfer_id`, `new_balance`

- [ ] **Step 8: Commit final**

```bash
git add .
git commit -m "feat(account): account-service complete - reactive WebFlux, R2DBC sharding, Kafka events"
```

---

## Critérios de aceite

- [ ] `POST /accounts` cria conta e retorna o shard correto (0 ou 1)
- [ ] Contas com IDs pares vão para shard-0, ímpares para shard-1 (baseado em `hashCode % 2`)
- [ ] `POST /accounts/{id}/debit` reduz saldo e publica `debit.completed` no Kafka
- [ ] `POST /accounts/{id}/debit` com saldo insuficiente retorna 400 e publica `debit.failed`
- [ ] Segundo débito com mesmo `transfer_id` retorna 409 (idempotency)
- [ ] 2 pods Running no namespace `fintech`
- [ ] `/actuator/health` retorna UP
- [ ] PostgreSQL shard-0 e shard-1 ativos com tabelas criadas
