#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

STRIMZI_VERSION="0.40.0"

# ─── Minikube ────────────────────────────────────────────────────────────────

if ! command -v minikube &>/dev/null; then
  echo "ERRO: minikube não encontrado. Instale antes de continuar."
  exit 1
fi

MINIKUBE_STATUS=$(minikube status --format='{{.Host}}' 2>/dev/null || echo "Nonexistent")

if [ "$MINIKUBE_STATUS" = "Running" ]; then
  echo "==> Minikube já está rodando. Pulando start."
else
  echo "==> Iniciando minikube..."
  minikube start \
    --cpus=4 \
    --memory=10240 \
    --disk-size=40g \
    --driver=docker \
    --kubernetes-version=v1.28.0
fi

# ─── Addons ──────────────────────────────────────────────────────────────────

echo "==> Habilitando addons..."
minikube addons enable metrics-server
minikube addons enable ingress

# ─── Helm repos ──────────────────────────────────────────────────────────────

echo "==> Adicionando repos Helm..."
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# ─── Helpers ─────────────────────────────────────────────────────────────────

apply_dir() {
  local dir="$1"
  local label="$2"
  if [ -d "$dir" ] && compgen -G "$dir/*.yaml" > /dev/null 2>&1; then
    echo "==> Aplicando manifests: $label"
    kubectl apply -f "$dir/"
  fi
}

wait_crd() {
  local crd="$1"
  echo "    Aguardando CRD $crd..."
  until kubectl get crd "$crd" &>/dev/null; do sleep 3; done
}

# ─── Namespaces ──────────────────────────────────────────────────────────────

echo "==> Aplicando namespaces..."
kubectl apply -f "$REPO_ROOT/infra/k8s/namespaces.yaml"

# ─── Strimzi operator (CRDs do Kafka) ────────────────────────────────────────

echo "==> Instalando operador Strimzi v${STRIMZI_VERSION}..."
kubectl apply -f "https://strimzi.io/install/latest?namespace=kafka" -n kafka

echo "==> Aguardando CRDs do Strimzi ficarem prontos..."
wait_crd "kafkas.kafka.strimzi.io"
wait_crd "kafkanodepools.kafka.strimzi.io"
wait_crd "kafkatopics.kafka.strimzi.io"

echo "==> Aguardando operador Strimzi ficar pronto..."
kubectl rollout status deployment/strimzi-cluster-operator -n kafka --timeout=120s

# ─── Kafka cluster + topics ───────────────────────────────────────────────────

echo "==> Aplicando manifests: Kafka cluster e metrics config"
kubectl apply -f "$REPO_ROOT/infra/k8s/kafka/kafka-metrics-config.yaml"
kubectl apply -f "$REPO_ROOT/infra/k8s/kafka/kafka-cluster.yaml"

echo "==> Aguardando Kafka cluster ficar pronto (pode levar alguns minutos)..."
kubectl wait kafka/fintech-kafka \
  --for=condition=Ready \
  --timeout=300s \
  -n kafka

echo "==> Aplicando topics Kafka..."
kubectl apply -f "$REPO_ROOT/infra/k8s/kafka/topics.yaml"

# ─── Restante da infra ───────────────────────────────────────────────────────

apply_dir "$REPO_ROOT/infra/k8s/redis"      "Redis"
apply_dir "$REPO_ROOT/infra/k8s/dynamodb"   "DynamoDB Local"
apply_dir "$REPO_ROOT/infra/k8s/monitoring" "Monitoring"

# ─── Microservices ───────────────────────────────────────────────────────────

for svc in auth-service account-service transfer-service notification-service api-gateway; do
  apply_dir "$REPO_ROOT/$svc/k8s" "$svc"
done

echo ""
echo "==> Setup concluido!"
echo "    kubectl get pods -A   # verificar status"

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
