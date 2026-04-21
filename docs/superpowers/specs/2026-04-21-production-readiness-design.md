# Production-Readiness — LoadBalancer + HPA

**Date:** 2026-04-21  
**Scope:** Expor o api-gateway externamente via LoadBalancer e adicionar HorizontalPodAutoscaler (HPA) baseado em CPU para todos os microservices.

---

## Contexto

Todos os microservices já possuem:
- Health probes (liveness + readiness)
- Resource requests e limits definidos
- Anotações Prometheus
- metrics-server habilitado no setup.sh

O que falta: ponto de entrada externo e auto-scaling automático.

---

## Arquitetura

```
Internet / Cliente
      ↓
LoadBalancer (api-gateway:80)   ← único IP externo (minikube tunnel)
      ↓
api-gateway (HPA: 2–10 pods)
      ↓
┌─────────────────────────────────────────┐
│  auth-service      (HPA: 2–8 pods)      │
│  account-service   (HPA: 2–6 pods)      │
│  transfer-service  (HPA: 2–8 pods)      │
│  notification-svc  (HPA: 2–6 pods)      │
└─────────────────────────────────────────┘
         (todos ClusterIP internos)
```

O `notification-service` usa WebSocket com `sessionAffinity: ClientIP` para garantir que o mesmo cliente sempre chegue ao mesmo pod.

---

## Mudanças por arquivo

### 1. `api-gateway/k8s/service.yaml`
- `type: ClusterIP` → `type: LoadBalancer`
- Mantém `port: 80`, `targetPort: 3000`

### 2. `notification-service/k8s/service.yaml`
- Adiciona `sessionAffinity: ClientIP` para afinidade de sessão WebSocket

### 3. HPA — um `hpa.yaml` por serviço

| Serviço | Min | Max | CPU target | Justificativa |
|---------|-----|-----|------------|---------------|
| api-gateway | 2 | 10 | 60% | Primeiro a receber carga — escala antes de saturar |
| auth-service | 2 | 8 | 70% | Stateless após Redis |
| account-service | 2 | 6 | 70% | Limitado pelo sharding de DB (2 shards) |
| transfer-service | 2 | 8 | 70% | Kafka absorve picos, pode escalar com margem |
| notification-service | 2 | 6 | 70% | WebSocket com sessionAffinity |

Cada HPA usa `autoscaling/v2` com `resource.cpu` como métrica.

### 4. `infra/scripts/setup.sh`
- Aplicar os 5 `hpa.yaml` após o deploy dos microservices
- Adicionar mensagem orientando o usuário a rodar `minikube tunnel` para expor o LoadBalancer

---

## Arquivos criados/modificados

```
api-gateway/k8s/service.yaml              # modificado: LoadBalancer
notification-service/k8s/service.yaml     # modificado: sessionAffinity
api-gateway/k8s/hpa.yaml                  # novo
auth-service/k8s/hpa.yaml                 # novo
account-service/k8s/hpa.yaml              # novo
transfer-service/k8s/hpa.yaml             # novo
notification-service/k8s/hpa.yaml         # novo
infra/scripts/setup.sh                    # modificado: apply HPAs + instrução tunnel
```

---

## Comportamento esperado em carga

1. Cliente envia requisições para `127.0.0.1:80` (via minikube tunnel)
2. api-gateway recebe carga → ao atingir 60% CPU → K8s sobe novos pods (até 10)
3. Pods downstream escalam conforme própria CPU (70%)
4. notification-service: mesmo cliente sempre vai ao mesmo pod via sessionAffinity
5. Kafka isola picos entre transfer-service e account-service — se a fila crescer, os consumers processam no próprio ritmo sem derrubar o cluster

---

## Limitações conhecidas

- `sessionAffinity: ClientIP` funciona bem em Minikube mas não substitui um Redis pub/sub para WebSocket em produção com múltiplos nós
- HPA requer ~30s para reagir a picos (cooldown padrão do K8s)
- `account-service` limitado a max 6 réplicas pelo sharding de 2 shards de DB — mais réplicas sem mais shards causaria contenção
