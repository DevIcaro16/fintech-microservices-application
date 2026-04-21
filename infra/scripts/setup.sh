#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

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

# ─── Helm ────────────────────────────────────────────────────────────────────

echo "==> Adicionando repos Helm..."
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo add grafana https://grafana.github.io/helm-charts
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# ─── Manifests K8s ───────────────────────────────────────────────────────────

apply_dir() {
  local dir="$1"
  local label="$2"
  if [ -d "$dir" ] && compgen -G "$dir/*.yaml" > /dev/null 2>&1; then
    echo "==> Aplicando manifests: $label"
    kubectl apply -f "$dir/" --recursive
  fi
}

# Infra — namespaces primeiro, depois o restante
echo "==> Aplicando namespaces..."
kubectl apply -f "$REPO_ROOT/infra/k8s/namespaces.yaml"

apply_dir "$REPO_ROOT/infra/k8s/kafka"    "Kafka"
apply_dir "$REPO_ROOT/infra/k8s/redis"    "Redis"
apply_dir "$REPO_ROOT/infra/k8s/dynamodb" "DynamoDB Local"
apply_dir "$REPO_ROOT/infra/k8s/monitoring" "Monitoring"

# Microservices
for svc in auth-service account-service transfer-service api-gateway; do
  apply_dir "$REPO_ROOT/$svc/k8s" "$svc"
done

echo ""
echo "==> Setup concluido!"
echo "    kubectl get pods -A   # verificar status"
