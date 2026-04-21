# Production-Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expor o api-gateway externamente via LoadBalancer e adicionar HorizontalPodAutoscaler (HPA) baseado em CPU a todos os microservices para auto-scaling automático.

**Architecture:** O api-gateway muda de ClusterIP para LoadBalancer (único IP público, acessível via `minikube tunnel`). Cada microservice recebe um `hpa.yaml` usando `autoscaling/v2` com CPU como métrica. O notification-service recebe `sessionAffinity: ClientIP` para manter conexões WebSocket no mesmo pod. Os HPAs são aplicados automaticamente pelo loop existente em `setup.sh` (que já faz `kubectl apply -f <svc>/k8s/`).

**Tech Stack:** Kubernetes autoscaling/v2, metrics-server (já habilitado), Minikube LoadBalancer via tunnel.

---

## Mapa de arquivos

```
api-gateway/k8s/service.yaml              # modificado: type LoadBalancer
notification-service/k8s/service.yaml     # modificado: sessionAffinity ClientIP
api-gateway/k8s/hpa.yaml                  # novo: min=2 max=10 cpu=60%
auth-service/k8s/hpa.yaml                 # novo: min=2 max=8  cpu=70%
account-service/k8s/hpa.yaml             # novo: min=2 max=6  cpu=70%
transfer-service/k8s/hpa.yaml            # novo: min=2 max=8  cpu=70%
notification-service/k8s/hpa.yaml        # novo: min=2 max=6  cpu=70%
infra/scripts/setup.sh                    # modificado: aviso minikube tunnel no final
```

---

## Task 1: api-gateway — LoadBalancer

**Files:**
- Modify: `api-gateway/k8s/service.yaml`

- [ ] **Step 1: Substituir o conteúdo do service.yaml**

Substitua o arquivo inteiro por:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: api-gateway
  namespace: fintech
  labels:
    app: api-gateway
spec:
  selector:
    app: api-gateway
  ports:
    - name: http
      port: 80
      targetPort: 3000
  type: LoadBalancer
```

- [ ] **Step 2: Verificar o diff**

```bash
git diff api-gateway/k8s/service.yaml
```

Esperado: única mudança é `type: ClusterIP` → `type: LoadBalancer`.

- [ ] **Step 3: Commit**

```bash
git add api-gateway/k8s/service.yaml
git commit -m "feat(infra): expose api-gateway as LoadBalancer for external access"
```

---

## Task 2: notification-service — sessionAffinity

**Files:**
- Modify: `notification-service/k8s/service.yaml`

- [ ] **Step 1: Adicionar sessionAffinity ao service.yaml**

O arquivo atual termina com `type: ClusterIP`. Substitua o arquivo inteiro por:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: notification-service
  namespace: fintech
  labels:
    app: notification-service
spec:
  selector:
    app: notification-service
  ports:
    - name: http-ws
      port: 80
      targetPort: 3001
  type: ClusterIP
  sessionAffinity: ClientIP
```

- [ ] **Step 2: Verificar o diff**

```bash
git diff notification-service/k8s/service.yaml
```

Esperado: única adição é `sessionAffinity: ClientIP`.

- [ ] **Step 3: Commit**

```bash
git add notification-service/k8s/service.yaml
git commit -m "feat(infra): add sessionAffinity ClientIP to notification-service for WebSocket stickiness"
```

---

## Task 3: HPA — api-gateway

**Files:**
- Create: `api-gateway/k8s/hpa.yaml`

- [ ] **Step 1: Criar `api-gateway/k8s/hpa.yaml`**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: api-gateway
  namespace: fintech
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: api-gateway
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
```

- [ ] **Step 2: Verificar sintaxe**

```bash
kubectl apply --dry-run=client -f api-gateway/k8s/hpa.yaml
```

Esperado: `horizontalpodautoscaler.autoscaling/api-gateway configured (dry run)`

- [ ] **Step 3: Commit**

```bash
git add api-gateway/k8s/hpa.yaml
git commit -m "feat(infra): add HPA for api-gateway (min=2 max=10 cpu=60%)"
```

---

## Task 4: HPA — auth-service

**Files:**
- Create: `auth-service/k8s/hpa.yaml`

- [ ] **Step 1: Criar `auth-service/k8s/hpa.yaml`**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: auth-service
  namespace: fintech
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: auth-service
  minReplicas: 2
  maxReplicas: 8
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

- [ ] **Step 2: Verificar sintaxe**

```bash
kubectl apply --dry-run=client -f auth-service/k8s/hpa.yaml
```

Esperado: `horizontalpodautoscaler.autoscaling/auth-service configured (dry run)`

- [ ] **Step 3: Commit**

```bash
git add auth-service/k8s/hpa.yaml
git commit -m "feat(infra): add HPA for auth-service (min=2 max=8 cpu=70%)"
```

---

## Task 5: HPA — account-service

**Files:**
- Create: `account-service/k8s/hpa.yaml`

- [ ] **Step 1: Criar `account-service/k8s/hpa.yaml`**

Max=6 porque o account-service tem sharding fixo de 2 shards de Postgres — mais pods sem mais shards causaria contenção.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: account-service
  namespace: fintech
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: account-service
  minReplicas: 2
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

- [ ] **Step 2: Verificar sintaxe**

```bash
kubectl apply --dry-run=client -f account-service/k8s/hpa.yaml
```

Esperado: `horizontalpodautoscaler.autoscaling/account-service configured (dry run)`

- [ ] **Step 3: Commit**

```bash
git add account-service/k8s/hpa.yaml
git commit -m "feat(infra): add HPA for account-service (min=2 max=6 cpu=70%)"
```

---

## Task 6: HPA — transfer-service

**Files:**
- Create: `transfer-service/k8s/hpa.yaml`

- [ ] **Step 1: Criar `transfer-service/k8s/hpa.yaml`**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: transfer-service
  namespace: fintech
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: transfer-service
  minReplicas: 2
  maxReplicas: 8
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

- [ ] **Step 2: Verificar sintaxe**

```bash
kubectl apply --dry-run=client -f transfer-service/k8s/hpa.yaml
```

Esperado: `horizontalpodautoscaler.autoscaling/transfer-service configured (dry run)`

- [ ] **Step 3: Commit**

```bash
git add transfer-service/k8s/hpa.yaml
git commit -m "feat(infra): add HPA for transfer-service (min=2 max=8 cpu=70%)"
```

---

## Task 7: HPA — notification-service

**Files:**
- Create: `notification-service/k8s/hpa.yaml`

- [ ] **Step 1: Criar `notification-service/k8s/hpa.yaml`**

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: notification-service
  namespace: fintech
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: notification-service
  minReplicas: 2
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
```

- [ ] **Step 2: Verificar sintaxe**

```bash
kubectl apply --dry-run=client -f notification-service/k8s/hpa.yaml
```

Esperado: `horizontalpodautoscaler.autoscaling/notification-service configured (dry run)`

- [ ] **Step 3: Commit**

```bash
git add notification-service/k8s/hpa.yaml
git commit -m "feat(infra): add HPA for notification-service (min=2 max=6 cpu=70%)"
```

---

## Task 8: Atualizar setup.sh — instrução minikube tunnel

**Files:**
- Modify: `infra/scripts/setup.sh`

- [ ] **Step 1: Localizar o final do script**

```bash
tail -10 infra/scripts/setup.sh
```

Identifique a última linha do bloco de deploy dos microservices.

- [ ] **Step 2: Adicionar instrução de túnel ao final do script**

Após o loop `for svc in ...`, adicione:

```bash
echo ""
echo "========================================================"
echo " Cluster pronto!"
echo ""
echo " Para expor o api-gateway externamente, execute em"
echo " um terminal separado:"
echo ""
echo "   minikube tunnel"
echo ""
echo " Depois acesse: http://127.0.0.1/auth/..."
echo "                http://127.0.0.1/accounts/..."
echo "                http://127.0.0.1/transfers/..."
echo "                ws://127.0.0.1/ws?transfer_id=..."
echo "========================================================"
```

- [ ] **Step 3: Verificar que o script não quebrou**

```bash
bash -n infra/scripts/setup.sh
```

Esperado: nenhum erro de sintaxe (sem output).

- [ ] **Step 4: Confirmar que os HPAs serão aplicados automaticamente**

```bash
grep "kubectl apply" infra/scripts/setup.sh
```

Esperado: linha contendo `kubectl apply -f` com o loop dos serviços — os `hpa.yaml` criados nas tasks anteriores já ficam dentro de `<svc>/k8s/` e serão aplicados automaticamente.

- [ ] **Step 5: Commit**

```bash
git add infra/scripts/setup.sh
git commit -m "feat(infra): add minikube tunnel instructions to setup.sh output"
```
