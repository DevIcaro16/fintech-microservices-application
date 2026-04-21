# Infrastructure Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Configurar a fundação completa de infraestrutura Kubernetes — namespaces, NGINX Ingress, stack de observabilidade (Prometheus + Grafana + Loki + Tempo + OpenTelemetry Collector), Kafka, Redis Cluster e DynamoDB Local — antes de qualquer código de microservice.

**Architecture:** Toda a infra roda dentro do minikube. Helm gerencia charts de terceiros (ingress-nginx, kube-prometheus-stack, Loki, Tempo, Kafka, Redis Cluster). Manifests customizados gerenciam DynamoDB Local e OpenTelemetry Collector. Grafana é o painel unificado para os três pilares de observabilidade.

**Tech Stack:** minikube ≥1.32, Helm 3, kubectl, ingress-nginx, kube-prometheus-stack, Grafana Loki + Promtail, Grafana Tempo, OpenTelemetry Collector, Apache Kafka (Bitnami), Redis Cluster (Bitnami), amazon/dynamodb-local

---

## Planos desta série

- **[01] Infra Foundation** ← você está aqui
- [02] auth-service (Go)
- [03] api-gateway (Bun + Elysia)
- [04] account-service (Java + Spring WebFlux)
- [05] transfer-service (Go)
- [06] notification-service (Bun + Elysia)
- [07] Load tests (k6)

---

## Estrutura de arquivos

```
microservices/
├── infra/
│   ├── k8s/
│   │   ├── namespaces.yaml
│   │   └── monitoring/
│   │       └── otel-collector.yaml
│   ├── helm/
│   │   ├── ingress/
│   │   │   └── values.yaml
│   │   ├── monitoring/
│   │   │   ├── prometheus-values.yaml
│   │   │   ├── loki-values.yaml
│   │   │   └── tempo-values.yaml
│   │   ├── kafka/
│   │   │   └── values.yaml
│   │   ├── redis/
│   │   │   └── values.yaml
│   │   └── dynamodb-local/
│   │       └── manifest.yaml
│   └── scripts/
│       ├── setup.sh          (inicia minikube + adiciona repos Helm)
│       ├── kafka-topics.sh   (cria tópicos Kafka)
│       └── validate.sh       (smoke test de toda a infra)
```

---

## Task 1: Estrutura do repositório e setup do minikube

**Files:**
- Create: `infra/scripts/setup.sh`

- [ ] **Step 1: Verificar pré-requisitos**

```bash
minikube version
helm version
kubectl version --client
```

Esperado: minikube ≥1.32, Helm ≥3.12, kubectl ≥1.28

- [ ] **Step 2: Criar estrutura de diretórios**

```bash
mkdir -p infra/k8s/monitoring
mkdir -p infra/helm/{ingress,monitoring,kafka,redis,dynamodb-local}
mkdir -p infra/scripts
```

- [ ] **Step 3: Criar script de setup**

Criar `infra/scripts/setup.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "==> Iniciando minikube..."
minikube start \
  --cpus=4 \
  --memory=10240 \
  --disk-size=40g \
  --driver=docker \
  --kubernetes-version=v1.28.0

echo "==> Habilitando addons..."
minikube addons enable metrics-server
minikube addons enable ingress

echo "==> Adicionando repos Helm..."
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

echo "==> Setup concluido!"
```

```bash
chmod +x infra/scripts/setup.sh
```

- [ ] **Step 4: Executar o setup**

```bash
./infra/scripts/setup.sh
```

Esperado (final): `==> Setup concluido!`

- [ ] **Step 5: Verificar minikube**

```bash
minikube status
```

Esperado:
```
minikube
type: Control Plane
host: Running
kubelet: Running
apiserver: Running
kubeconfig: Configured
```

- [ ] **Step 6: Commit**

```bash
git init
git add infra/scripts/setup.sh
git commit -m "chore: initialize repo and minikube setup script"
```

---

## Task 2: Namespaces

**Files:**
- Create: `infra/k8s/namespaces.yaml`

- [ ] **Step 1: Verificar que os namespaces não existem ainda**

```bash
kubectl get namespaces | grep -E "fintech|monitoring|kafka|data"
```

Esperado: nenhuma linha retornada

- [ ] **Step 2: Criar manifest de namespaces**

Criar `infra/k8s/namespaces.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: fintech
  labels:
    app.kubernetes.io/managed-by: kubectl
---
apiVersion: v1
kind: Namespace
metadata:
  name: monitoring
  labels:
    app.kubernetes.io/managed-by: kubectl
---
apiVersion: v1
kind: Namespace
metadata:
  name: kafka
  labels:
    app.kubernetes.io/managed-by: kubectl
---
apiVersion: v1
kind: Namespace
metadata:
  name: data
  labels:
    app.kubernetes.io/managed-by: kubectl
```

- [ ] **Step 3: Aplicar**

```bash
kubectl apply -f infra/k8s/namespaces.yaml
```

Esperado:
```
namespace/fintech created
namespace/monitoring created
namespace/kafka created
namespace/data created
```

- [ ] **Step 4: Verificar**

```bash
kubectl get namespaces | grep -E "fintech|monitoring|kafka|data"
```

Esperado:
```
data        Active   Xs
fintech     Active   Xs
kafka       Active   Xs
monitoring  Active   Xs
```

- [ ] **Step 5: Commit**

```bash
git add infra/k8s/namespaces.yaml
git commit -m "chore: add kubernetes namespaces"
```

---

## Task 3: NGINX Ingress Controller

**Files:**
- Create: `infra/helm/ingress/values.yaml`

- [ ] **Step 1: Verificar que o Ingress Controller não está instalado**

```bash
helm list -n ingress-nginx 2>/dev/null || echo "namespace not found"
```

Esperado: `namespace not found` ou lista vazia

- [ ] **Step 2: Criar values do Ingress**

Criar `infra/helm/ingress/values.yaml`:

```yaml
controller:
  replicaCount: 1
  resources:
    requests:
      cpu: 100m
      memory: 128Mi
    limits:
      cpu: 500m
      memory: 512Mi
  service:
    type: NodePort
  metrics:
    enabled: true
    serviceMonitor:
      enabled: true
      namespace: monitoring
  config:
    use-gzip: "true"
    log-format-upstream: >-
      {"time":"$time_iso8601","remote_addr":"$remote_addr",
      "request":"$request","status":"$status",
      "bytes_sent":"$bytes_sent","trace_id":"$http_x_trace_id"}
```

- [ ] **Step 3: Instalar**

```bash
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  --namespace ingress-nginx \
  --create-namespace \
  --values infra/helm/ingress/values.yaml \
  --wait \
  --timeout 5m
```

Esperado (última linha): `STATUS: deployed`

- [ ] **Step 4: Verificar pods**

```bash
kubectl get pods -n ingress-nginx
```

Esperado:
```
NAME                                        READY   STATUS    RESTARTS
ingress-nginx-controller-XXXXXXXXX-XXXXX   1/1     Running   0
```

- [ ] **Step 5: Commit**

```bash
git add infra/helm/ingress/values.yaml
git commit -m "chore: add nginx ingress controller"
```

---

## Task 4: Prometheus + Grafana (kube-prometheus-stack)

**Files:**
- Create: `infra/helm/monitoring/prometheus-values.yaml`

- [ ] **Step 1: Verificar que a stack não está instalada**

```bash
helm list -n monitoring
```

Esperado: lista vazia

- [ ] **Step 2: Criar values do kube-prometheus-stack**

Criar `infra/helm/monitoring/prometheus-values.yaml`:

```yaml
grafana:
  enabled: true
  adminPassword: "admin"
  persistence:
    enabled: false
  service:
    type: NodePort
    nodePort: 32000
  grafana.ini:
    feature_toggles:
      enable: traceqlEditor
  additionalDataSources:
    - name: Loki
      type: loki
      url: http://loki-gateway.monitoring.svc.cluster.local:80
      access: proxy
      isDefault: false
    - name: Tempo
      type: tempo
      url: http://tempo.monitoring.svc.cluster.local:3100
      access: proxy
      isDefault: false
      jsonData:
        tracesToLogsV2:
          datasourceUid: loki
        lokiSearch:
          datasourceUid: loki

prometheus:
  prometheusSpec:
    scrapeInterval: 15s
    evaluationInterval: 15s
    retention: 7d
    resources:
      requests:
        cpu: 200m
        memory: 512Mi
      limits:
        cpu: 500m
        memory: 1Gi
    serviceMonitorSelectorNilUsesHelmValues: false
    podMonitorSelectorNilUsesHelmValues: false

alertmanager:
  enabled: false

nodeExporter:
  enabled: true

kubeStateMetrics:
  enabled: true
```

- [ ] **Step 3: Instalar**

```bash
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --namespace monitoring \
  --values infra/helm/monitoring/prometheus-values.yaml \
  --wait \
  --timeout 10m
```

Esperado (última linha): `STATUS: deployed`

- [ ] **Step 4: Verificar pods**

```bash
kubectl get pods -n monitoring -l "release=kube-prometheus-stack"
```

Esperado: todos os pods com `STATUS: Running`

- [ ] **Step 5: Acessar Grafana**

```bash
minikube service kube-prometheus-stack-grafana -n monitoring --url
```

Abrir a URL no browser. Login: `admin` / `admin`.
Esperado: dashboard do Grafana carregado.

- [ ] **Step 6: Commit**

```bash
git add infra/helm/monitoring/prometheus-values.yaml
git commit -m "chore: add kube-prometheus-stack (prometheus + grafana)"
```

---

## Task 5: Loki + Promtail

**Files:**
- Create: `infra/helm/monitoring/loki-values.yaml`

- [ ] **Step 1: Verificar que Loki não está instalado**

```bash
helm list -n monitoring | grep loki
```

Esperado: nenhuma linha

- [ ] **Step 2: Criar values do Loki**

Criar `infra/helm/monitoring/loki-values.yaml`:

```yaml
loki:
  enabled: true
  isDefault: false
  persistence:
    enabled: false
  config:
    auth_enabled: false
    ingester:
      chunk_idle_period: 3m
      chunk_block_size: 262144
      chunk_retain_period: 1m
    schema_config:
      configs:
        - from: "2024-01-01"
          store: tsdb
          object_store: filesystem
          schema: v13
          index:
            prefix: index_
            period: 24h
    storage_config:
      filesystem:
        directory: /data/loki/chunks
  resources:
    requests:
      cpu: 100m
      memory: 256Mi
    limits:
      cpu: 300m
      memory: 512Mi

promtail:
  enabled: true
  config:
    clients:
      - url: http://loki:3100/loki/api/v1/push
    snippets:
      pipelineStages:
        - cri: {}
        - json:
            expressions:
              level: level
              trace_id: trace_id
              service: service
        - labels:
            level:
            trace_id:
            service:
  resources:
    requests:
      cpu: 50m
      memory: 64Mi
    limits:
      cpu: 100m
      memory: 128Mi

grafana:
  enabled: false
```

- [ ] **Step 3: Instalar**

```bash
helm upgrade --install loki grafana/loki-stack \
  --namespace monitoring \
  --values infra/helm/monitoring/loki-values.yaml \
  --wait \
  --timeout 5m
```

Esperado: `STATUS: deployed`

- [ ] **Step 4: Verificar pods**

```bash
kubectl get pods -n monitoring | grep -E "loki|promtail"
```

Esperado: pods `loki-0` e `loki-promtail-XXXXX` com `Running`

- [ ] **Step 5: Verificar datasource Loki no Grafana**

```bash
GRAFANA_URL=$(minikube service kube-prometheus-stack-grafana -n monitoring --url)
curl -s -u admin:admin "$GRAFANA_URL/api/datasources" | grep -o '"name":"[^"]*"'
```

Esperado: `"name":"Loki"` na saída

- [ ] **Step 6: Commit**

```bash
git add infra/helm/monitoring/loki-values.yaml
git commit -m "chore: add loki + promtail for log aggregation"
```

---

## Task 6: Tempo (distributed tracing)

**Files:**
- Create: `infra/helm/monitoring/tempo-values.yaml`

- [ ] **Step 1: Verificar que Tempo não está instalado**

```bash
helm list -n monitoring | grep tempo
```

Esperado: nenhuma linha

- [ ] **Step 2: Criar values do Tempo**

Criar `infra/helm/monitoring/tempo-values.yaml`:

```yaml
tempo:
  repository: grafana/tempo
  tag: latest
  resources:
    requests:
      cpu: 100m
      memory: 256Mi
    limits:
      cpu: 300m
      memory: 512Mi

config: |
  multitenancy_enabled: false
  usage_report:
    reporting_enabled: false
  compactor:
    compaction:
      block_retention: 48h
  distributor:
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: 0.0.0.0:4317
          http:
            endpoint: 0.0.0.0:4318
  ingester:
    max_block_duration: 5m
  server:
    http_listen_port: 3100
  storage:
    trace:
      backend: local
      local:
        path: /var/tempo/traces
      wal:
        path: /var/tempo/wal

persistence:
  enabled: false

serviceMonitor:
  enabled: true
  namespace: monitoring
```

- [ ] **Step 3: Instalar**

```bash
helm upgrade --install tempo grafana/tempo \
  --namespace monitoring \
  --values infra/helm/monitoring/tempo-values.yaml \
  --wait \
  --timeout 5m
```

Esperado: `STATUS: deployed`

- [ ] **Step 4: Verificar pod**

```bash
kubectl get pods -n monitoring | grep tempo
```

Esperado: `tempo-0` com `STATUS: Running`

- [ ] **Step 5: Verificar porta OTLP exposta**

```bash
kubectl get svc -n monitoring | grep tempo
```

Esperado: service `tempo` com portas `3100/TCP,4317/TCP,4318/TCP`

- [ ] **Step 6: Commit**

```bash
git add infra/helm/monitoring/tempo-values.yaml
git commit -m "chore: add tempo for distributed tracing"
```

---

## Task 7: OpenTelemetry Collector

**Files:**
- Create: `infra/k8s/monitoring/otel-collector.yaml`

- [ ] **Step 1: Verificar que o OTel Collector não existe**

```bash
kubectl get deployment otel-collector -n monitoring 2>&1
```

Esperado: `Error from server (NotFound)`

- [ ] **Step 2: Criar manifest do OTel Collector**

Criar `infra/k8s/monitoring/otel-collector.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: otel-collector-config
  namespace: monitoring
data:
  config.yaml: |
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: 0.0.0.0:4317
          http:
            endpoint: 0.0.0.0:4318

    processors:
      batch:
        timeout: 1s
        send_batch_size: 1024
      resource:
        attributes:
          - key: cluster
            value: fintech-local
            action: insert

    exporters:
      otlp/tempo:
        endpoint: tempo.monitoring.svc.cluster.local:4317
        tls:
          insecure: true
      debug:
        verbosity: basic

    service:
      pipelines:
        traces:
          receivers: [otlp]
          processors: [batch, resource]
          exporters: [otlp/tempo, debug]
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: otel-collector
  namespace: monitoring
  labels:
    app: otel-collector
spec:
  replicas: 1
  selector:
    matchLabels:
      app: otel-collector
  template:
    metadata:
      labels:
        app: otel-collector
    spec:
      containers:
        - name: otel-collector
          image: otel/opentelemetry-collector-contrib:0.96.0
          args: ["--config=/etc/otel/config.yaml"]
          ports:
            - containerPort: 4317
              name: otlp-grpc
            - containerPort: 4318
              name: otlp-http
          volumeMounts:
            - name: config
              mountPath: /etc/otel
          resources:
            requests:
              cpu: 50m
              memory: 64Mi
            limits:
              cpu: 200m
              memory: 256Mi
      volumes:
        - name: config
          configMap:
            name: otel-collector-config
---
apiVersion: v1
kind: Service
metadata:
  name: otel-collector
  namespace: monitoring
  labels:
    app: otel-collector
spec:
  selector:
    app: otel-collector
  ports:
    - name: otlp-grpc
      port: 4317
      targetPort: 4317
    - name: otlp-http
      port: 4318
      targetPort: 4318
```

- [ ] **Step 3: Aplicar**

```bash
kubectl apply -f infra/k8s/monitoring/otel-collector.yaml
```

Esperado:
```
configmap/otel-collector-config created
deployment.apps/otel-collector created
service/otel-collector created
```

- [ ] **Step 4: Verificar pod**

```bash
kubectl get pods -n monitoring | grep otel-collector
```

Esperado: `otel-collector-XXXXXXXXX-XXXXX` com `STATUS: Running`

- [ ] **Step 5: Verificar logs do collector**

```bash
kubectl logs -n monitoring deployment/otel-collector --tail=20
```

Esperado: logs sem erros, mostrando `Everything is ready`

- [ ] **Step 6: Commit**

```bash
git add infra/k8s/monitoring/otel-collector.yaml
git commit -m "chore: add opentelemetry collector"
```

---

## Task 8: Validar stack de observabilidade

**Files:** nenhum (validação apenas)

- [ ] **Step 1: Verificar todos os pods de monitoring**

```bash
kubectl get pods -n monitoring
```

Esperado: todos com `STATUS: Running` ou `Completed`. Nenhum `CrashLoopBackOff`.

- [ ] **Step 2: Verificar datasources no Grafana via API**

```bash
GRAFANA_URL=$(minikube service kube-prometheus-stack-grafana -n monitoring --url)
curl -s -u admin:admin "$GRAFANA_URL/api/datasources" | python3 -c \
  "import sys,json; ds=json.load(sys.stdin); [print(d['name']) for d in ds]"
```

Esperado:
```
Prometheus
Loki
Tempo
```

- [ ] **Step 3: Verificar Prometheus scraping**

```bash
GRAFANA_URL=$(minikube service kube-prometheus-stack-grafana -n monitoring --url)
curl -s -u admin:admin \
  "$GRAFANA_URL/api/datasources/proxy/uid/$(curl -s -u admin:admin $GRAFANA_URL/api/datasources | python3 -c "import sys,json; ds=json.load(sys.stdin); print(next(d['uid'] for d in ds if d['name']=='Prometheus'))")/api/v1/query?query=up" \
  | python3 -c "import sys,json; r=json.load(sys.stdin); print(f'Targets up: {len(r[\"data\"][\"result\"])}')"
```

Esperado: `Targets up: N` onde N > 0

- [ ] **Step 4: Commit (nenhum arquivo novo, apenas validação)**

```bash
git commit --allow-empty -m "chore: observability stack validated"
```

---

## Task 9: Kafka Cluster

**Files:**
- Create: `infra/helm/kafka/values.yaml`

- [ ] **Step 1: Verificar que Kafka não está instalado**

```bash
helm list -n kafka
```

Esperado: lista vazia

- [ ] **Step 2: Criar values do Kafka**

Criar `infra/helm/kafka/values.yaml`:

```yaml
kraft:
  enabled: true

replicaCount: 1

controller:
  replicaCount: 1
  persistence:
    enabled: false

broker:
  replicaCount: 0

resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 1Gi

listeners:
  client:
    protocol: PLAINTEXT
  interbroker:
    protocol: PLAINTEXT
  controller:
    protocol: PLAINTEXT

provisioning:
  enabled: false

metrics:
  kafka:
    enabled: true
  jmx:
    enabled: true
  serviceMonitor:
    enabled: true
    namespace: monitoring

extraConfig: |
  auto.create.topics.enable=false
  default.replication.factor=1
  min.insync.replicas=1
  log.retention.hours=168
```

- [ ] **Step 3: Instalar**

```bash
helm upgrade --install kafka bitnami/kafka \
  --namespace kafka \
  --values infra/helm/kafka/values.yaml \
  --wait \
  --timeout 10m
```

Esperado: `STATUS: deployed`

- [ ] **Step 4: Verificar pod**

```bash
kubectl get pods -n kafka
```

Esperado: `kafka-controller-0` com `STATUS: Running`

- [ ] **Step 5: Testar conectividade com o broker**

```bash
kubectl exec -it kafka-controller-0 -n kafka -- \
  kafka-broker-api-versions.sh \
  --bootstrap-server kafka.kafka.svc.cluster.local:9092
```

Esperado: lista de APIs suportadas sem erros de conexão

- [ ] **Step 6: Commit**

```bash
git add infra/helm/kafka/values.yaml
git commit -m "chore: add kafka cluster (kraft mode)"
```

---

## Task 10: Criação dos tópicos Kafka

**Files:**
- Create: `infra/scripts/kafka-topics.sh`

- [ ] **Step 1: Verificar que os tópicos não existem**

```bash
kubectl exec -it kafka-controller-0 -n kafka -- \
  kafka-topics.sh --bootstrap-server kafka.kafka.svc.cluster.local:9092 --list
```

Esperado: saída vazia ou apenas tópicos internos (`__consumer_offsets`)

- [ ] **Step 2: Criar script de criação de tópicos**

Criar `infra/scripts/kafka-topics.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

BOOTSTRAP="kafka.kafka.svc.cluster.local:9092"
PARTITIONS=3
REPLICATION=1

TOPICS=(
  "transfer.requested"
  "debit.completed"
  "debit.failed"
  "credit.completed"
  "credit.failed"
  "transfer.completed"
  "transfer.failed"
  "debit.reversal"
)

echo "==> Criando topicos Kafka..."
for topic in "${TOPICS[@]}"; do
  kubectl exec -it kafka-controller-0 -n kafka -- \
    kafka-topics.sh \
    --bootstrap-server "$BOOTSTRAP" \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions "$PARTITIONS" \
    --replication-factor "$REPLICATION"
  echo "  [OK] $topic"
done

echo ""
echo "==> Topicos criados:"
kubectl exec -it kafka-controller-0 -n kafka -- \
  kafka-topics.sh --bootstrap-server "$BOOTSTRAP" --list
```

```bash
chmod +x infra/scripts/kafka-topics.sh
```

- [ ] **Step 3: Executar o script**

```bash
./infra/scripts/kafka-topics.sh
```

Esperado: cada tópico com `[OK]` e listagem final com os 8 tópicos

- [ ] **Step 4: Verificar tópicos criados**

```bash
kubectl exec -it kafka-controller-0 -n kafka -- \
  kafka-topics.sh --bootstrap-server kafka.kafka.svc.cluster.local:9092 --list
```

Esperado:
```
credit.completed
credit.failed
debit.completed
debit.failed
debit.reversal
transfer.completed
transfer.failed
transfer.requested
```

- [ ] **Step 5: Commit**

```bash
git add infra/scripts/kafka-topics.sh
git commit -m "chore: add kafka topics creation script"
```

---

## Task 11: Redis Cluster

**Files:**
- Create: `infra/helm/redis/values.yaml`

- [ ] **Step 1: Verificar que Redis não está instalado**

```bash
helm list -n data | grep redis
```

Esperado: nenhuma linha

- [ ] **Step 2: Criar values do Redis Cluster**

Criar `infra/helm/redis/values.yaml`:

```yaml
cluster:
  nodes: 3
  replicas: 0

persistence:
  enabled: false

redis:
  resources:
    requests:
      cpu: 100m
      memory: 128Mi
    limits:
      cpu: 300m
      memory: 256Mi

password: ""
usePassword: false

metrics:
  enabled: true
  serviceMonitor:
    enabled: true
    namespace: monitoring
```

- [ ] **Step 3: Instalar**

```bash
helm upgrade --install redis-cluster bitnami/redis-cluster \
  --namespace data \
  --values infra/helm/redis/values.yaml \
  --wait \
  --timeout 10m
```

Esperado: `STATUS: deployed`

- [ ] **Step 4: Verificar pods**

```bash
kubectl get pods -n data | grep redis
```

Esperado: `redis-cluster-0`, `redis-cluster-1`, `redis-cluster-2` todos com `Running`

- [ ] **Step 5: Verificar estado do cluster**

```bash
kubectl exec -it redis-cluster-0 -n data -- \
  redis-cli cluster info | grep cluster_state
```

Esperado: `cluster_state:ok`

- [ ] **Step 6: Verificar distribuição de slots**

```bash
kubectl exec -it redis-cluster-0 -n data -- \
  redis-cli cluster nodes | awk '{print $3, $9}'
```

Esperado: 3 linhas com `master` e ranges de slots distintos (ex: `0-5460`, `5461-10922`, `10923-16383`)

- [ ] **Step 7: Commit**

```bash
git add infra/helm/redis/values.yaml
git commit -m "chore: add redis cluster (3 shards)"
```

---

## Task 12: DynamoDB Local

**Files:**
- Create: `infra/helm/dynamodb-local/manifest.yaml`

- [ ] **Step 1: Verificar que DynamoDB Local não existe**

```bash
kubectl get deployment dynamodb-local -n data 2>&1
```

Esperado: `Error from server (NotFound)`

- [ ] **Step 2: Criar manifest**

Criar `infra/helm/dynamodb-local/manifest.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: dynamodb-local
  namespace: data
  labels:
    app: dynamodb-local
spec:
  replicas: 1
  selector:
    matchLabels:
      app: dynamodb-local
  template:
    metadata:
      labels:
        app: dynamodb-local
    spec:
      containers:
        - name: dynamodb-local
          image: amazon/dynamodb-local:2.3.0
          args: ["-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory"]
          ports:
            - containerPort: 8000
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 300m
              memory: 512Mi
---
apiVersion: v1
kind: Service
metadata:
  name: dynamodb-local
  namespace: data
  labels:
    app: dynamodb-local
spec:
  selector:
    app: dynamodb-local
  ports:
    - port: 8000
      targetPort: 8000
      name: http
```

- [ ] **Step 3: Aplicar**

```bash
kubectl apply -f infra/helm/dynamodb-local/manifest.yaml
```

Esperado:
```
deployment.apps/dynamodb-local created
service/dynamodb-local created
```

- [ ] **Step 4: Verificar pod**

```bash
kubectl get pods -n data | grep dynamodb
```

Esperado: `dynamodb-local-XXXXXXXXX-XXXXX` com `STATUS: Running`

- [ ] **Step 5: Criar tabelas iniciais via port-forward**

A imagem `amazon/dynamodb-local` não inclui o AWS CLI — use `kubectl port-forward` e o AWS CLI local (requer `aws` instalado na máquina).

```bash
# Em um terminal separado, mantenha o port-forward ativo:
kubectl port-forward -n data svc/dynamodb-local 8000:8000 &
PF_PID=$!
sleep 2

aws dynamodb create-table \
  --table-name transfer-history \
  --attribute-definitions \
    AttributeName=user_id,AttributeType=S \
    AttributeName=created_at,AttributeType=S \
  --key-schema \
    AttributeName=user_id,KeyType=HASH \
    AttributeName=created_at,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url http://localhost:8000 \
  --region us-east-1 \
  --no-cli-pager

aws dynamodb create-table \
  --table-name notification-log \
  --attribute-definitions \
    AttributeName=user_id,AttributeType=S \
    AttributeName=created_at,AttributeType=S \
  --key-schema \
    AttributeName=user_id,KeyType=HASH \
    AttributeName=created_at,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url http://localhost:8000 \
  --region us-east-1 \
  --no-cli-pager

kill $PF_PID
```

Esperado: JSON com `"TableStatus": "ACTIVE"` para cada tabela.
Obs: se `aws` não estiver configurado localmente, use `AWS_ACCESS_KEY_ID=local AWS_SECRET_ACCESS_KEY=local` como prefixo dos comandos.

- [ ] **Step 6: Verificar tabelas**

```bash
kubectl port-forward -n data svc/dynamodb-local 8000:8000 &
PF_PID=$!
sleep 2

aws dynamodb list-tables \
  --endpoint-url http://localhost:8000 \
  --region us-east-1 \
  --no-cli-pager

kill $PF_PID
```

Esperado:
```json
{
    "TableNames": [
        "notification-log",
        "transfer-history"
    ]
}
```

- [ ] **Step 7: Commit**

```bash
git add infra/helm/dynamodb-local/manifest.yaml
git commit -m "chore: add dynamodb-local with initial tables"
```

---

## Task 13: Smoke test completo da infraestrutura

**Files:**
- Create: `infra/scripts/validate.sh`

- [ ] **Step 1: Criar script de validação**

Criar `infra/scripts/validate.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

PASS=0
FAIL=0

check() {
  local description="$1"
  local command="$2"
  local expected="$3"

  output=$(eval "$command" 2>&1)
  if echo "$output" | grep -q "$expected"; then
    echo "  [PASS] $description"
    PASS=$((PASS + 1))
  else
    echo "  [FAIL] $description"
    echo "         Esperado: '$expected'"
    echo "         Obtido:   '$output'"
    FAIL=$((FAIL + 1))
  fi
}

echo "==> Namespaces"
check "namespace fintech" "kubectl get ns fintech" "Active"
check "namespace monitoring" "kubectl get ns monitoring" "Active"
check "namespace kafka" "kubectl get ns kafka" "Active"
check "namespace data" "kubectl get ns data" "Active"

echo ""
echo "==> Ingress"
check "nginx ingress running" \
  "kubectl get pods -n ingress-nginx" "Running"

echo ""
echo "==> Observabilidade"
check "prometheus running" \
  "kubectl get pods -n monitoring -l app.kubernetes.io/name=prometheus" "Running"
check "grafana running" \
  "kubectl get pods -n monitoring -l app.kubernetes.io/name=grafana" "Running"
check "loki running" \
  "kubectl get pods -n monitoring -l app=loki" "Running"
check "tempo running" \
  "kubectl get pods -n monitoring -l app.kubernetes.io/name=tempo" "Running"
check "otel-collector running" \
  "kubectl get pods -n monitoring -l app=otel-collector" "Running"

echo ""
echo "==> Kafka"
check "kafka running" \
  "kubectl get pods -n kafka" "Running"
check "kafka topics" \
  "kubectl exec -it kafka-controller-0 -n kafka -- kafka-topics.sh --bootstrap-server kafka.kafka.svc.cluster.local:9092 --list" \
  "transfer.requested"

echo ""
echo "==> Redis Cluster"
check "redis nodes running" \
  "kubectl get pods -n data -l app.kubernetes.io/name=redis-cluster" "Running"
check "redis cluster ok" \
  "kubectl exec -it redis-cluster-0 -n data -- redis-cli cluster info" \
  "cluster_state:ok"

echo ""
echo "==> DynamoDB Local"
check "dynamodb running" \
  "kubectl get pods -n data -l app=dynamodb-local" "Running"
check "dynamodb tables" \
  "kubectl port-forward -n data svc/dynamodb-local 8000:8000 & sleep 2 && aws dynamodb list-tables --endpoint-url http://localhost:8000 --region us-east-1 --no-cli-pager; kill %1 2>/dev/null" \
  "transfer-history"

echo ""
echo "========================================="
echo "  RESULT: $PASS passed, $FAIL failed"
echo "========================================="

[ "$FAIL" -eq 0 ]
```

```bash
chmod +x infra/scripts/validate.sh
```

- [ ] **Step 2: Executar validação completa**

```bash
./infra/scripts/validate.sh
```

Esperado:
```
==> Namespaces
  [PASS] namespace fintech
  [PASS] namespace monitoring
  [PASS] namespace kafka
  [PASS] namespace data
...
=========================================
  RESULT: 14 passed, 0 failed
=========================================
```

- [ ] **Step 3: Commit final**

```bash
git add infra/scripts/validate.sh
git commit -m "chore: add infra smoke test script — all checks passing"
```

---

## Resumo do que foi construído

| Componente | Namespace | Acesso interno |
|---|---|---|
| NGINX Ingress | ingress-nginx | `ingress-nginx-controller` |
| Prometheus | monitoring | `kube-prometheus-stack-prometheus:9090` |
| Grafana | monitoring | NodePort 32000 |
| Loki | monitoring | `loki:3100` |
| Promtail | monitoring | DaemonSet nos nodes |
| Tempo | monitoring | `tempo:3100` (query), `tempo:4317` (OTLP gRPC) |
| OTel Collector | monitoring | `otel-collector:4317` (gRPC), `otel-collector:4318` (HTTP) |
| Kafka | kafka | `kafka.kafka.svc.cluster.local:9092` |
| Redis Cluster | data | `redis-cluster.data.svc.cluster.local:6379` |
| DynamoDB Local | data | `dynamodb-local.data.svc.cluster.local:8000` |

**Próximo plano:** `2026-04-20-02-auth-service.md` — auth-service em Go com goroutines, JWT, PostgreSQL e Redis para blacklist.
