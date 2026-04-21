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
