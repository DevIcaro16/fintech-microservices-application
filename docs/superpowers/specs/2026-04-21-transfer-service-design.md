# Transfer Service — Design Spec
**Date:** 2026-04-21  
**Stack:** Java 21 + Spring Boot 3 + WebFlux  
**Branch:** feature/transfer-service

---

## 1. Objetivo

O `transfer-service` é o ponto de entrada para transferências entre contas. Ele:
1. Recebe a requisição REST e inicia o Saga publicando `transfer-requested`
2. Acompanha o estado da transferência reagindo a eventos Kafka do `account-service`
3. Notifica o solicitante via webhook quando a transferência conclui (com retry persistido em DynamoDB)

---

## 2. Estados da Transferência

```
PENDING → DEBITED → COMPLETED
                 ↘ REVERTING → FAILED
       ↘ FAILED  (debit-failed direto)
```

| Estado | Significado |
|---|---|
| `PENDING` | Publicado `transfer-requested`, aguardando debit |
| `DEBITED` | `debit-completed` recebido, aguardando credit |
| `REVERTING` | `debit-reversal` recebido, reversão em andamento |
| `COMPLETED` | `credit-completed` recebido |
| `FAILED` | `debit-failed` ou reversão concluída |

Transições inválidas são ignoradas (idempotência por estado).

---

## 3. API REST

```
POST /transfers
  Body:  { source_account_id, destination_account_id, amount, callback_url }
  Resp:  202 Accepted { transfer_id, status: "PENDING" }

GET /transfers/{id}
  Resp:  { transfer_id, status, source_account_id, destination_account_id,
           amount, created_at, updated_at }

GET /transfers?user_id={uid}
  Resp:  [ { transfer_id, status, amount, created_at } ]
  Fonte: DynamoDB transfer-history (lookup por user_id)

GET /healthz → 200 { status: "ok" }
```

Validações no POST: campos obrigatórios, `amount > 0`, `source ≠ destination`.  
`user_id` no GET é o `source_account_id` (owner da conta de origem).

---

## 4. Fluxo Kafka

### Produz
| Tópico | Quando |
|---|---|
| `transfer-requested` | Imediatamente após salvar Transfer(PENDING) |
| `transfer-completed` | Ao receber `credit-completed` |
| `transfer-failed` | Ao receber `debit-failed` ou `debit-reversal` |

### Consome
| Tópico | Ação |
|---|---|
| `debit-completed` | PENDING → DEBITED |
| `credit-completed` | DEBITED → COMPLETED + grava history + agenda webhook |
| `debit-failed` | PENDING → FAILED + agenda webhook |
| `debit-reversal` | DEBITED → REVERTING → FAILED + agenda webhook |

Ack manual (`MANUAL_IMMEDIATE`). Payload inválido: loga e ack para evitar poison pill.

---

## 5. Webhook e Notificação

**Fluxo:**
1. Evento final (COMPLETED/FAILED) → grava `NotificationEntry` no DynamoDB `notification-log`
2. `NotificationScheduler` roda a cada 5s, busca entradas com `status=PENDING` e `nextRetryAt ≤ now` via GSI
3. `WebhookNotifier` faz POST no `callback_url` via WebClient (timeout 5s)
4. Sucesso → `delivered=true`, `status=DELIVERED`
5. Falha → incrementa `attempts`, recalcula `nextRetryAt` (backoff: 1s→2s→4s→8s→16s)
6. Após 5 tentativas → `status=EXHAUSTED`, loga erro crítico

**Payload do webhook:**
```json
{
  "transfer_id": "...",
  "status": "COMPLETED|FAILED",
  "source_account_id": "...",
  "destination_account_id": "...",
  "amount": "100.00"
}
```

---

## 6. Persistência

### PostgreSQL — tabela `transfers`
```sql
CREATE TABLE transfers (
  id                     VARCHAR(36) PRIMARY KEY,
  source_account_id      VARCHAR(36) NOT NULL,
  destination_account_id VARCHAR(36) NOT NULL,
  amount                 NUMERIC(19,2) NOT NULL,
  status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  callback_url           TEXT,
  user_id                VARCHAR(36) NOT NULL,
  created_at             TIMESTAMPTZ DEFAULT NOW(),
  updated_at             TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_transfers_user ON transfers(user_id);
```

### DynamoDB — `transfer-history`
PK: `user_id` (=source_account_id), SK: `created_at`  
Escrita quando status vai para COMPLETED ou FAILED.

### DynamoDB — `notification-log`
PK: `transfer_id`, SK: `created_at`  
Atributos: `callback_url`, `payload`, `attempts`, `next_retry_at`, `status` (PENDING/DELIVERED/EXHAUSTED)  
GSI: `status-next_retry_at-index` (PK: `status`, SK: `next_retry_at`) — usado pelo scheduler.

> **Nota:** O `create-tables.sh` em `infra/k8s/dynamodb/` precisa ser atualizado para incluir a tabela `notification-log` com o GSI e ajustar o schema de `transfer-history`.

---

## 7. Arquitetura Hexagonal

```
domain/
  Transfer.java           — entidade + transições de estado
  TransferStatus.java     — enum
  NotificationEntry.java  — entidade DynamoDB

port/in/
  TransferUseCase.java    — create, findById, applyEvent

port/out/
  TransferRepository.java       — save, findById (Postgres R2DBC)
  TransferHistoryPort.java      — save (DynamoDB transfer-history)
  NotificationPort.java         — save, findPending, markDelivered, markExhausted
  EventPort.java                — publish (Kafka)

application/
  TransferService.java          — orquestra casos de uso

adapter/in/
  web/
    TransferHandler.java
    RouterConfig.java
  messaging/
    KafkaTransferEventConsumer.java

adapter/out/
  persistence/
    PostgresTransferRepository.java
  dynamodb/
    DynamoDbHistoryAdapter.java
    DynamoDbNotificationAdapter.java
  messaging/
    KafkaEventAdapter.java
  webhook/
    WebhookNotifier.java
    NotificationScheduler.java

config/
  R2dbcConfig.java
  KafkaConfig.java
  DynamoDbConfig.java     — DynamoDbAsyncClient apontando para DynamoDB Local
```

---

## 8. Dependências (build.gradle)

```groovy
spring-boot-starter-webflux
spring-boot-starter-data-r2dbc
spring-boot-starter-actuator
spring-kafka
r2dbc-postgresql:1.0.5.RELEASE
postgresql (JDBC — Testcontainers init)
software.amazon.awssdk:dynamodb:2.25.67
software.amazon.awssdk:netty-nio-client:2.25.67
micrometer-registry-prometheus
jackson-databind

// test
spring-boot-starter-test
reactor-test
mockito-core
testcontainers:postgresql:1.20.6
testcontainers:junit-jupiter:1.20.6
// DynamoDB Local via Testcontainers generic container
```

---

## 9. Testes

| Tipo | Classe | Escopo |
|---|---|---|
| Unit | `TransferServiceTest` | transições de estado, validações |
| Unit | `KafkaTransferEventConsumerTest` | cada evento → estado correto + webhook agendado |
| Web | `TransferHandlerTest` (`@WebFluxTest`) | POST/GET, 400/404/409 |
| IT | `PostgresTransferRepositoryIT` (Testcontainers) | save/findById/updateStatus |
| IT | `DynamoDbAdapterIT` (Testcontainers generic) | history + notification-log CRUD |

**Fix Testcontainers obrigatório** (`build.gradle`):
```groovy
test {
    systemProperty 'api.version', '1.44'
    environment 'TESTCONTAINERS_RYUK_DISABLED', 'true'
}
```

---

## 10. Infra K8s

```
k8s/
  deployment.yaml     — 2 réplicas, probes /actuator/health, resources
  service.yaml        — ClusterIP porta 80 → 8080
  configmap.yaml      — DB_URL, KAFKA_BOOTSTRAP, DYNAMODB_ENDPOINT
  postgres.yaml       — StatefulSet (1 réplica, sem sharding)
```

---

## 11. O que NÃO está no escopo

- Autenticação/autorização (responsabilidade do `api-gateway`)
- Rate limiting (responsabilidade do `api-gateway`)
- Notificações por email/SMS (fora do escopo do `notification-log`)
- Cancelamento de transferência em andamento
