#!/usr/bin/env bash
# Run via: kubectl port-forward svc/dynamodb-local 8000:8000 -n data &
# Then: bash infra/k8s/dynamodb/create-tables.sh
set -e

ENDPOINT="http://localhost:8000"

echo "==> Creating transfer-history table"
aws dynamodb create-table \
  --table-name transfer-history \
  --attribute-definitions \
    AttributeName=user_id,AttributeType=S \
    AttributeName=created_at,AttributeType=S \
  --key-schema \
    AttributeName=user_id,KeyType=HASH \
    AttributeName=created_at,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url "$ENDPOINT" \
  --region us-east-1

echo "==> Creating notification-log table (with GSI for retry scheduler)"
aws dynamodb create-table \
  --table-name notification-log \
  --attribute-definitions \
    AttributeName=transfer_id,AttributeType=S \
    AttributeName=created_at,AttributeType=S \
    AttributeName=status,AttributeType=S \
    AttributeName=next_retry_at,AttributeType=S \
  --key-schema \
    AttributeName=transfer_id,KeyType=HASH \
    AttributeName=created_at,KeyType=RANGE \
  --global-secondary-indexes '[
    {
      "IndexName": "status-next_retry_at-index",
      "KeySchema": [
        {"AttributeName": "status", "KeyType": "HASH"},
        {"AttributeName": "next_retry_at", "KeyType": "RANGE"}
      ],
      "Projection": {"ProjectionType": "ALL"}
    }
  ]' \
  --billing-mode PAY_PER_REQUEST \
  --endpoint-url "$ENDPOINT" \
  --region us-east-1

echo "==> Tables created:"
aws dynamodb list-tables --endpoint-url "$ENDPOINT" --region us-east-1
