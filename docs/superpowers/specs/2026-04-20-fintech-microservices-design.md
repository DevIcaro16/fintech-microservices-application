# Fintech Microservices Platform — Design Spec

**Date:** 2026-04-20  
**Status:** Approved  

## Objetivo

Plataforma fintech enxuta inspirada em Nubank/Itaú para aprendizado prático de microservices com múltiplas linguagens, consistência distribuída (Saga + Outbox), arquitetura orientada a eventos (Kafka), cache distribuído, sharding de banco de dados e DevOps com Kubernetes.

---

## 1. Serviços

| Serviço | Linguagem | Arquitetura interna | Responsabilidade |
|---|---|---|---|
| `api-gateway` | Bun + Elysia | Pipeline de middlewares | Ponto de entrada único, roteamento, rate limiting, propagação de headers |
| `auth-service` | Go | Layered simples (handler → service → repo) | Emissão e validação de JWT, login, refresh token, blacklist |
| `account-service` | Java + Spring WebFlux | Hexagonal (Ports & Adapters) | Criação de contas, saldo, débito/crédito reativo com R2DBC |
| `transfer-service` | Go | Clean Architecture | Orquestra o Saga de transferência via Kafka, idempotency, outbox |
| `notification-service` | Bun + Elysia | Pipeline simples (consumer → handler) | Consome eventos Kafka, entrega notificações via WebSocket em tempo real |

---

## 2. Performance por Linguagem

### Go (`auth-service` e `transfer-service`)
- Goroutines para operações concorrentes (validação paralela de tokens, etapas do Saga)
- Channels para pipeline de eventos e rate limiting interno
- Worker pools para consumo de Kafka

### Java + Spring WebFlux (`account-service`)
- Mono/Flux para todas as operações — zero threads bloqueantes
- R2DBC como driver reativo para PostgreSQL (não JDBC)
- Backpressure configurado no pipeline reativo
- Nenhum uso de drivers bloqueantes (evitar o anti-pattern JDBC + WebFlux)

### Bun + Elysia (`api-gateway` e `notification-service`)
- Event loop nativo do Bun — async/await em todas as operações
- Promise pipeline para Kafka consumer no `notification-service`
- WebSocket para entrega de notificações em tempo real
- Connection pooling no gateway

---

## 3. Fluxo de Negócio — Transferência entre Contas

Fluxo principal usando **Choreography Saga** (sem orquestrador central):

```
Usuário → api-gateway → auth-service (valida JWT)
                      ↓
               transfer-service
                      ↓ publica evento
              [Kafka: TransferRequested]
                      ↓
               account-service (debita conta origem)
                      ↓ publica evento
              [Kafka: DebitCompleted]
                      ↓
               account-service (credita conta destino)
                      ↓ publica evento
              [Kafka: CreditCompleted]
                      ↓
               transfer-service (marca transferência concluída)
                      ↓ publica evento
              [Kafka: TransferCompleted]
                      ↓
               notification-service (notifica os dois usuários via WebSocket)
```

### Compensação (rollback distribuído)

Se o crédito falhar após débito já realizado:

```
[Kafka: CreditFailed]
       ↓
transfer-service publica [Kafka: DebitReversal]
       ↓
account-service estorna o débito
       ↓
[Kafka: TransferFailed]
       ↓
notification-service notifica falha ao usuário
```

### Outbox Pattern

Cada serviço que produz eventos grava na tabela `outbox` dentro da **mesma transação** que altera o estado do negócio. Um worker separado lê a tabela e publica no Kafka — garante que nenhum evento se perde mesmo em falha do Kafka.

```sql
CREATE TABLE outbox (
  id          UUID PRIMARY KEY,
  aggregate_id VARCHAR NOT NULL,
  event_type  VARCHAR NOT NULL,
  payload     JSONB   NOT NULL,
  published   BOOLEAN DEFAULT FALSE,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);
```

---

## 4. Banco de Dados

### Database-per-service
Nenhum serviço acessa o banco de outro diretamente. Toda comunicação de dados entre serviços ocorre via eventos Kafka.

### Bancos por serviço

| Serviço | Banco principal | Cache |
|---|---|---|
| `auth-service` | PostgreSQL | Redis Cluster — blacklist de JWTs, refresh tokens |
| `account-service` | PostgreSQL (2 shards) | Redis Cluster — cache de saldo por `account_id` (TTL curto) |
| `transfer-service` | PostgreSQL + DynamoDB Local | Redis Cluster — idempotency keys |
| `notification-service` | DynamoDB Local | — |

### Sharding no `account-service`

Sharding lógico com 2 instâncias de PostgreSQL:
- `account_id` par → `postgres-shard-0`
- `account_id` ímpar → `postgres-shard-1`

O `account-service` é responsável pelo shard resolver antes de qualquer query. Permite aprender sobre queries cross-shard, escolha de chave de shard e consistência entre shards.

### DynamoDB Local

Usado para histórico de transferências (`transfer-service`) e log de notificações (`notification-service`). Roda como `amazon/dynamodb-local` dentro do Kubernetes — sem dependência de AWS.

| Serviço | Partition Key | Sort Key | Conceito explorado |
|---|---|---|---|
| `transfer-service` | `user_id` | `created_at` | Sharding automático por partition key |
| `notification-service` | `user_id` | `created_at` | Hot partition problem (demonstração) |

### Redis Cluster

3 nós com slots distribuídos:
- nó-1: slots 0–5460
- nó-2: slots 5461–10922
- nó-3: slots 10923–16383

Demonstra consistência de cache sob failover, rebalanceamento de slots e por que cache centralizado não escala.

---

## 5. Kafka — Tópicos e Eventos

| Tópico | Produzido por | Consumido por |
|---|---|---|
| `transfer.requested` | `transfer-service` | `account-service` |
| `debit.completed` | `account-service` | `transfer-service` |
| `debit.failed` | `account-service` | `transfer-service` |
| `credit.completed` | `account-service` | `transfer-service` |
| `credit.failed` | `account-service` | `transfer-service` |
| `transfer.completed` | `transfer-service` | `notification-service` |
| `transfer.failed` | `transfer-service` | `notification-service` |
| `debit.reversal` | `transfer-service` | `account-service` |

---

## 6. Infraestrutura — Kubernetes

### Topologia

```
Internet
    ↓
[Ingress Controller - NGINX]   ← load balancer externo
    ↓
[api-gateway - ClusterIP]      ← múltiplos pods
    ↓
[auth / account / transfer / notification Services]
  (3 pods)   (3 pods)   (2 pods)       (2 pods)
```

### Componentes

| Componente | Função |
|---|---|
| NGINX Ingress | Entry point, TLS termination, rate limiting por IP |
| HPA (Horizontal Pod Autoscaler) | Escala pods automaticamente baseado em CPU/memória |
| Helm Charts | Um chart por serviço para gerenciamento de deploy |
| k6 | Load testing — simula milhares de usuários concorrentes |
| `amazon/dynamodb-local` | DynamoDB emulado dentro do cluster |

### Ambiente local
`minikube` com `--cpus=4 --memory=8192`.

---

## 7. Observabilidade

Stack unificada no **Grafana** (único painel para os três pilares):

| Pilar | Ferramenta | Coleta |
|---|---|---|
| Métricas | Prometheus + Grafana | `/metrics` endpoint em cada serviço |
| Logs | Loki + Grafana | Logs JSON via Promtail no k8s |
| Tracing | Tempo + Grafana | OpenTelemetry SDK em cada serviço |

### `trace_id` propagado em toda a cadeia

Headers HTTP e eventos Kafka carregam o `trace_id` — permite rastrear uma transferência do `api-gateway` até o `notification-service` em um único trace distribuído.

### Métricas específicas

- `transfer-service`: Sagas iniciadas, concluídas, compensadas
- `account-service`: Latência de queries reativas, backpressure events
- `api-gateway`: RPS, latência p99, taxa de erro por rota
- Redis Cluster: cache hit rate, slot rebalancing events

---

## 8. Ordem de Desenvolvimento

A construção segue a ordem abaixo — infraestrutura como fundação, depois cada serviço individualmente:

1. **Infra base** — Kubernetes (minikube), NGINX Ingress, namespaces, ConfigMaps e Secrets
2. **Observabilidade** — Prometheus, Grafana, Loki + Promtail, Tempo, OpenTelemetry Collector
3. **Kafka + Zookeeper** — cluster Kafka dentro do k8s, criação de tópicos
4. **Redis Cluster** — 3 nós dentro do k8s
5. **DynamoDB Local** — `amazon/dynamodb-local` no k8s, tabelas iniciais
6. **`auth-service`** (Go) — PostgreSQL próprio, JWT, Redis para blacklist
7. **`api-gateway`** (Bun + Elysia) — roteamento, integração com `auth-service`
8. **`account-service`** (Java + Spring WebFlux) — PostgreSQL shardado, R2DBC, Redis para saldo
9. **`transfer-service`** (Go) — PostgreSQL + DynamoDB, Saga, Outbox, Redis idempotency
10. **`notification-service`** (Bun + Elysia) — DynamoDB, Kafka consumer, WebSocket
11. **Load tests** — scripts k6 + validação de HPA + observabilidade sob carga

Cada serviço é desenvolvido junto com o usuário: implementação → deploy no k8s → validação via Grafana.

---

## 9. Estrutura de Repositório

```
microservices/
├── api-gateway/          # Bun + Elysia
├── auth-service/         # Go
├── account-service/      # Java + Spring WebFlux
├── transfer-service/     # Go
├── notification-service/ # Bun + Elysia
├── infra/
│   ├── k8s/              # manifests Kubernetes
│   ├── helm/             # Helm charts por serviço
│   ├── kafka/            # configuração de tópicos
│   └── monitoring/       # Prometheus, Grafana, Loki, Tempo
├── load-tests/           # scripts k6
└── docs/
    └── superpowers/
        └── specs/
```

---

## 10. Conceitos Aplicados (resumo)

| Conceito | Onde é aplicado |
|---|---|
| Choreography Saga | `transfer-service` + `account-service` via Kafka |
| Outbox Pattern | `transfer-service`, `account-service` |
| Database-per-service | Todos os serviços |
| Sharding lógico | `account-service` (2 shards Postgres) |
| Sharding gerenciado | `transfer-service` + `notification-service` (DynamoDB) |
| Cache distribuído | Redis Cluster (3 nós) |
| Idempotency | `transfer-service` via Redis |
| Reactive programming | `account-service` (WebFlux + R2DBC + Mono/Flux) |
| Goroutines + channels | `auth-service`, `transfer-service` |
| Event loop async | `api-gateway`, `notification-service` |
| Load balancing + HPA | Kubernetes + NGINX Ingress |
| Observabilidade completa | Prometheus + Loki + Tempo + Grafana |
| Distributed tracing | OpenTelemetry em todos os serviços |
| Load testing | k6 |
