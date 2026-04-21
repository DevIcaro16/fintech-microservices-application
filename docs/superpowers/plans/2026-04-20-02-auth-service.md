# Auth Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar o `auth-service` em Go com arquitetura Layered (handler → service → repo), JWT (emissão, validação, refresh), blacklist de tokens no Redis e PostgreSQL próprio — tudo deployado no Kubernetes com observabilidade.

**Architecture:** Layered simples: HTTP handler recebe requisição → service executa lógica de negócio + JWT → repo persiste/consulta no PostgreSQL. Redis armazena blacklist de JWTs revogados e refresh tokens com TTL. OpenTelemetry SDK instrumenta cada camada. O serviço expõe `/metrics` para o Prometheus.

**Tech Stack:** Go 1.22, Chi router, golang-jwt/jwt v5, pgx/v5 (PostgreSQL driver), go-redis/redis v9, OpenTelemetry Go SDK, Prometheus client_golang, Docker, Kubernetes (namespace `fintech`), Helm, PostgreSQL 16, Redis Cluster (já provisionado no namespace `data`).

---

## Planos desta série

- [01] Infra Foundation ✅
- **[02] auth-service (Go)** ← você está aqui
- [03] api-gateway (Bun + Elysia)
- [04] account-service (Java + Spring WebFlux)
- [05] transfer-service (Go)
- [06] notification-service (Bun + Elysia)
- [07] Load tests (k6)

---

## Estrutura de arquivos

```
auth-service/
├── cmd/
│   └── server/
│       └── main.go               # entrypoint: wires deps, starts HTTP server
├── internal/
│   ├── handler/
│   │   └── auth_handler.go       # HTTP handlers (login, refresh, logout, validate)
│   ├── service/
│   │   └── auth_service.go       # JWT logic, goroutine-based parallel validation
│   ├── repo/
│   │   ├── user_repo.go          # PostgreSQL: users table
│   │   └── token_repo.go         # Redis: blacklist + refresh tokens
│   ├── middleware/
│   │   └── otel.go               # OpenTelemetry trace + metrics middleware
│   └── model/
│       └── model.go              # User, TokenPair, Claims structs
├── migrations/
│   └── 001_init.sql              # users table + outbox table
├── Dockerfile
├── go.mod
├── go.sum
└── k8s/
    ├── deployment.yaml
    ├── service.yaml
    ├── configmap.yaml
    ├── secret.yaml
    └── postgres.yaml             # PostgreSQL StatefulSet para o auth-service
```

---

## Contratos de API

```
POST /auth/login
  Body: { "email": "string", "password": "string" }
  200:  { "access_token": "string", "refresh_token": "string", "expires_in": 900 }
  401:  { "error": "invalid credentials" }

POST /auth/refresh
  Body: { "refresh_token": "string" }
  200:  { "access_token": "string", "refresh_token": "string", "expires_in": 900 }
  401:  { "error": "invalid or expired refresh token" }

POST /auth/logout
  Header: Authorization: Bearer <access_token>
  200:  { "message": "logged out" }

GET /auth/validate
  Header: Authorization: Bearer <access_token>
  200:  { "user_id": "uuid", "email": "string", "valid": true }
  401:  { "error": "token invalid or blacklisted" }

GET /healthz    → 200 OK
GET /metrics    → Prometheus metrics
```

---

## Task 1: Estrutura do projeto Go e dependências

**Files:**
- Create: `auth-service/go.mod`
- Create: `auth-service/go.sum` (gerado)
- Create: `auth-service/cmd/server/main.go`
- Create: `auth-service/internal/model/model.go`

- [ ] **Step 1: Inicializar módulo Go**

```bash
mkdir -p auth-service && cd auth-service
go mod init github.com/DevIcaro16/fintech-microservices-application/auth-service
```

- [ ] **Step 2: Adicionar dependências**

```bash
go get github.com/go-chi/chi/v5@v5.1.0
go get github.com/golang-jwt/jwt/v5@v5.2.1
go get github.com/jackc/pgx/v5@v5.6.0
go get github.com/redis/go-redis/v9@v9.5.3
go get golang.org/x/crypto@v0.22.0
go get go.opentelemetry.io/otel@v1.26.0
go get go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc@v1.26.0
go get go.opentelemetry.io/otel/sdk@v1.26.0
go get go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp@v0.51.0
go get github.com/prometheus/client_golang@v1.19.0
```

- [ ] **Step 3: Criar model.go**

```go
// auth-service/internal/model/model.go
package model

import "time"

type User struct {
    ID           string    `db:"id"`
    Email        string    `db:"email"`
    PasswordHash string    `db:"password_hash"`
    CreatedAt    time.Time `db:"created_at"`
}

type TokenPair struct {
    AccessToken  string `json:"access_token"`
    RefreshToken string `json:"refresh_token"`
    ExpiresIn    int    `json:"expires_in"`
}

type Claims struct {
    UserID string `json:"user_id"`
    Email  string `json:"email"`
    // jwt.RegisteredClaims embedded below in service layer
}

type LoginRequest struct {
    Email    string `json:"email"`
    Password string `json:"password"`
}

type RefreshRequest struct {
    RefreshToken string `json:"refresh_token"`
}

type ValidateResponse struct {
    UserID string `json:"user_id"`
    Email  string `json:"email"`
    Valid  bool   `json:"valid"`
}
```

- [ ] **Step 4: Criar main.go esqueleto (apenas compilação por enquanto)**

```go
// auth-service/cmd/server/main.go
package main

import (
    "log"
    "net/http"
    "os"
)

func main() {
    port := os.Getenv("PORT")
    if port == "" {
        port = "8080"
    }
    log.Printf("auth-service starting on :%s", port)
    if err := http.ListenAndServe(":"+port, nil); err != nil {
        log.Fatal(err)
    }
}
```

- [ ] **Step 5: Verificar compilação**

```bash
cd auth-service && go build ./...
```

Expected: sem erros.

- [ ] **Step 6: Commit**

```bash
git add auth-service/
git commit -m "feat(auth): initialize Go module and project structure"
```

---

## Task 2: Migração e PostgreSQL

**Files:**
- Create: `auth-service/migrations/001_init.sql`
- Create: `auth-service/k8s/postgres.yaml`

- [ ] **Step 1: Criar migration SQL**

```sql
-- auth-service/migrations/001_init.sql
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS outbox (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_id VARCHAR NOT NULL,
    event_type   VARCHAR NOT NULL,
    payload      JSONB   NOT NULL,
    published    BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_outbox_published ON outbox(published) WHERE published = FALSE;
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
```

- [ ] **Step 2: Criar manifest PostgreSQL para o auth-service**

```yaml
# auth-service/k8s/postgres.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: auth-postgres
  namespace: fintech
spec:
  serviceName: auth-postgres
  replicas: 1
  selector:
    matchLabels:
      app: auth-postgres
  template:
    metadata:
      labels:
        app: auth-postgres
    spec:
      containers:
        - name: postgres
          image: postgres:16-alpine
          env:
            - name: POSTGRES_DB
              value: authdb
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: auth-postgres-secret
                  key: username
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: auth-postgres-secret
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
            limits:
              cpu: 300m
              memory: 512Mi
      volumes:
        - name: data
          emptyDir: {}
        - name: migrations
          configMap:
            name: auth-migrations
---
apiVersion: v1
kind: Service
metadata:
  name: auth-postgres
  namespace: fintech
spec:
  clusterIP: None
  selector:
    app: auth-postgres
  ports:
    - port: 5432
---
apiVersion: v1
kind: Secret
metadata:
  name: auth-postgres-secret
  namespace: fintech
type: Opaque
stringData:
  username: authuser
  password: authpass123
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: auth-migrations
  namespace: fintech
data:
  001_init.sql: |
    CREATE EXTENSION IF NOT EXISTS "pgcrypto";
    CREATE TABLE IF NOT EXISTS users (
        id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        email         VARCHAR(255) UNIQUE NOT NULL,
        password_hash VARCHAR(255) NOT NULL,
        created_at    TIMESTAMPTZ DEFAULT NOW()
    );
    CREATE TABLE IF NOT EXISTS outbox (
        id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        aggregate_id VARCHAR NOT NULL,
        event_type   VARCHAR NOT NULL,
        payload      JSONB   NOT NULL,
        published    BOOLEAN DEFAULT FALSE,
        created_at   TIMESTAMPTZ DEFAULT NOW()
    );
    CREATE INDEX IF NOT EXISTS idx_outbox_published ON outbox(published) WHERE published = FALSE;
    CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
```

- [ ] **Step 3: Aplicar no Kubernetes**

```bash
kubectl apply -f auth-service/k8s/postgres.yaml
kubectl wait statefulset auth-postgres -n fintech --for=jsonpath='{.status.readyReplicas}'=1 --timeout=60s
```

Expected: `statefulset.apps/auth-postgres condition met`

- [ ] **Step 4: Verificar tabelas**

```bash
kubectl exec -n fintech auth-postgres-0 -- psql -U authuser -d authdb -c "\dt"
```

Expected: listagem com `users` e `outbox`.

- [ ] **Step 5: Commit**

```bash
git add auth-service/migrations/ auth-service/k8s/postgres.yaml
git commit -m "feat(auth): add PostgreSQL StatefulSet and schema migrations"
```

---

## Task 3: Repositório (repo layer)

**Files:**
- Create: `auth-service/internal/repo/user_repo.go`
- Create: `auth-service/internal/repo/token_repo.go`

- [ ] **Step 1: Criar user_repo.go**

```go
// auth-service/internal/repo/user_repo.go
package repo

import (
    "context"

    "github.com/DevIcaro16/fintech-microservices-application/auth-service/internal/model"
    "github.com/jackc/pgx/v5/pgxpool"
)

type UserRepo struct {
    db *pgxpool.Pool
}

func NewUserRepo(db *pgxpool.Pool) *UserRepo {
    return &UserRepo{db: db}
}

func (r *UserRepo) FindByEmail(ctx context.Context, email string) (*model.User, error) {
    u := &model.User{}
    err := r.db.QueryRow(ctx,
        `SELECT id, email, password_hash, created_at FROM users WHERE email = $1`,
        email,
    ).Scan(&u.ID, &u.Email, &u.PasswordHash, &u.CreatedAt)
    if err != nil {
        return nil, err
    }
    return u, nil
}

func (r *UserRepo) FindByID(ctx context.Context, id string) (*model.User, error) {
    u := &model.User{}
    err := r.db.QueryRow(ctx,
        `SELECT id, email, password_hash, created_at FROM users WHERE id = $1`,
        id,
    ).Scan(&u.ID, &u.Email, &u.PasswordHash, &u.CreatedAt)
    if err != nil {
        return nil, err
    }
    return u, nil
}

func (r *UserRepo) Create(ctx context.Context, email, passwordHash string) (*model.User, error) {
    u := &model.User{}
    err := r.db.QueryRow(ctx,
        `INSERT INTO users (email, password_hash) VALUES ($1, $2)
         RETURNING id, email, password_hash, created_at`,
        email, passwordHash,
    ).Scan(&u.ID, &u.Email, &u.PasswordHash, &u.CreatedAt)
    if err != nil {
        return nil, err
    }
    return u, nil
}
```

- [ ] **Step 2: Criar token_repo.go**

```go
// auth-service/internal/repo/token_repo.go
package repo

import (
    "context"
    "time"

    "github.com/redis/go-redis/v9"
)

type TokenRepo struct {
    rdb *redis.ClusterClient
}

func NewTokenRepo(rdb *redis.ClusterClient) *TokenRepo {
    return &TokenRepo{rdb: rdb}
}

// Blacklist: guarda jti do access token até expirar
func (r *TokenRepo) BlacklistToken(ctx context.Context, jti string, ttl time.Duration) error {
    return r.rdb.Set(ctx, "blacklist:"+jti, "1", ttl).Err()
}

func (r *TokenRepo) IsBlacklisted(ctx context.Context, jti string) (bool, error) {
    res, err := r.rdb.Exists(ctx, "blacklist:"+jti).Result()
    if err != nil {
        return false, err
    }
    return res > 0, nil
}

// Refresh tokens: guarda userID → refreshToken com TTL de 7 dias
func (r *TokenRepo) SaveRefreshToken(ctx context.Context, userID, token string, ttl time.Duration) error {
    return r.rdb.Set(ctx, "refresh:"+userID, token, ttl).Err()
}

func (r *TokenRepo) GetRefreshToken(ctx context.Context, userID string) (string, error) {
    return r.rdb.Get(ctx, "refresh:"+userID).Result()
}

func (r *TokenRepo) DeleteRefreshToken(ctx context.Context, userID string) error {
    return r.rdb.Del(ctx, "refresh:"+userID).Err()
}
```

- [ ] **Step 3: Verificar compilação**

```bash
cd auth-service && go build ./...
```

Expected: sem erros.

- [ ] **Step 4: Commit**

```bash
git add auth-service/internal/repo/
git commit -m "feat(auth): add user and token repository layer (PostgreSQL + Redis)"
```

---

## Task 4: Service layer com JWT e goroutines

**Files:**
- Create: `auth-service/internal/service/auth_service.go`

- [ ] **Step 1: Criar auth_service.go**

```go
// auth-service/internal/service/auth_service.go
package service

import (
    "context"
    "errors"
    "os"
    "time"

    "github.com/DevIcaro16/fintech-microservices-application/auth-service/internal/model"
    "github.com/DevIcaro16/fintech-microservices-application/auth-service/internal/repo"
    "github.com/golang-jwt/jwt/v5"
    "golang.org/x/crypto/bcrypt"
)

var (
    ErrInvalidCredentials = errors.New("invalid credentials")
    ErrTokenBlacklisted   = errors.New("token is blacklisted")
    ErrInvalidToken       = errors.New("invalid token")
)

type AuthClaims struct {
    UserID string `json:"user_id"`
    Email  string `json:"email"`
    jwt.RegisteredClaims
}

type AuthService struct {
    users  *repo.UserRepo
    tokens *repo.TokenRepo
    secret []byte
}

func NewAuthService(users *repo.UserRepo, tokens *repo.TokenRepo) *AuthService {
    secret := os.Getenv("JWT_SECRET")
    if secret == "" {
        secret = "changeme-insecure-dev-secret"
    }
    return &AuthService{users: users, tokens: tokens, secret: []byte(secret)}
}

func (s *AuthService) Login(ctx context.Context, email, password string) (*model.TokenPair, error) {
    user, err := s.users.FindByEmail(ctx, email)
    if err != nil {
        return nil, ErrInvalidCredentials
    }
    if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(password)); err != nil {
        return nil, ErrInvalidCredentials
    }
    return s.issueTokenPair(ctx, user)
}

func (s *AuthService) Refresh(ctx context.Context, refreshToken string) (*model.TokenPair, error) {
    claims, err := s.parseToken(refreshToken)
    if err != nil {
        return nil, ErrInvalidToken
    }
    stored, err := s.tokens.GetRefreshToken(ctx, claims.UserID)
    if err != nil || stored != refreshToken {
        return nil, ErrInvalidToken
    }
    user, err := s.users.FindByID(ctx, claims.UserID)
    if err != nil {
        return nil, ErrInvalidToken
    }
    return s.issueTokenPair(ctx, user)
}

func (s *AuthService) Logout(ctx context.Context, accessToken string) error {
    claims, err := s.parseToken(accessToken)
    if err != nil {
        return ErrInvalidToken
    }
    ttl := time.Until(claims.ExpiresAt.Time)
    if ttl <= 0 {
        return nil // já expirou, não precisa blacklistar
    }
    if err := s.tokens.BlacklistToken(ctx, claims.ID, ttl); err != nil {
        return err
    }
    return s.tokens.DeleteRefreshToken(ctx, claims.UserID)
}

// ValidateParallel usa goroutines para checar assinatura e blacklist em paralelo.
func (s *AuthService) ValidateParallel(ctx context.Context, accessToken string) (*model.ValidateResponse, error) {
    type result struct {
        claims      *AuthClaims
        blacklisted bool
        err         error
    }

    claimsCh := make(chan result, 1)
    blacklistCh := make(chan result, 1)

    // goroutine 1: valida assinatura JWT
    go func() {
        claims, err := s.parseToken(accessToken)
        claimsCh <- result{claims: claims, err: err}
    }()

    // goroutine 2: checa blacklist (precisamos do jti — só possível após parse)
    // fazemos parse rápido sem validação de expiração para obter o jti
    jti := extractJTI(accessToken)
    go func() {
        if jti == "" {
            blacklistCh <- result{blacklisted: false}
            return
        }
        bl, err := s.tokens.IsBlacklisted(ctx, jti)
        blacklistCh <- result{blacklisted: bl, err: err}
    }()

    claimsRes := <-claimsCh
    blRes := <-blacklistCh

    if claimsRes.err != nil {
        return nil, ErrInvalidToken
    }
    if blRes.err != nil {
        return nil, blRes.err
    }
    if blRes.blacklisted {
        return nil, ErrTokenBlacklisted
    }
    return &model.ValidateResponse{
        UserID: claimsRes.claims.UserID,
        Email:  claimsRes.claims.Email,
        Valid:  true,
    }, nil
}

func (s *AuthService) issueTokenPair(ctx context.Context, user *model.User) (*model.TokenPair, error) {
    now := time.Now()
    accessClaims := AuthClaims{
        UserID: user.ID,
        Email:  user.Email,
        RegisteredClaims: jwt.RegisteredClaims{
            ID:        newJTI(),
            IssuedAt:  jwt.NewNumericDate(now),
            ExpiresAt: jwt.NewNumericDate(now.Add(15 * time.Minute)),
        },
    }
    access, err := jwt.NewWithClaims(jwt.SigningMethodHS256, accessClaims).SignedString(s.secret)
    if err != nil {
        return nil, err
    }

    refreshClaims := AuthClaims{
        UserID: user.ID,
        Email:  user.Email,
        RegisteredClaims: jwt.RegisteredClaims{
            ID:        newJTI(),
            IssuedAt:  jwt.NewNumericDate(now),
            ExpiresAt: jwt.NewNumericDate(now.Add(7 * 24 * time.Hour)),
        },
    }
    refresh, err := jwt.NewWithClaims(jwt.SigningMethodHS256, refreshClaims).SignedString(s.secret)
    if err != nil {
        return nil, err
    }

    if err := s.tokens.SaveRefreshToken(ctx, user.ID, refresh, 7*24*time.Hour); err != nil {
        return nil, err
    }
    return &model.TokenPair{AccessToken: access, RefreshToken: refresh, ExpiresIn: 900}, nil
}

func (s *AuthService) parseToken(tokenStr string) (*AuthClaims, error) {
    claims := &AuthClaims{}
    _, err := jwt.ParseWithClaims(tokenStr, claims, func(t *jwt.Token) (any, error) {
        if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
            return nil, ErrInvalidToken
        }
        return s.secret, nil
    })
    return claims, err
}

func extractJTI(tokenStr string) string {
    // Parse sem validação apenas para obter o jti antes da goroutine de blacklist
    claims := &AuthClaims{}
    p := jwt.NewParser()
    t, _, _ := p.ParseUnverified(tokenStr, claims)
    if t == nil {
        return ""
    }
    return claims.ID
}

func newJTI() string {
    // UUID v4 simples via crypto/rand
    b := make([]byte, 16)
    _, _ = cryptoRandRead(b)
    b[6] = (b[6] & 0x0f) | 0x40
    b[8] = (b[8] & 0x3f) | 0x80
    return fmt.Sprintf("%08x-%04x-%04x-%04x-%12x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}
```

**Nota:** `cryptoRandRead` e `fmt` precisam ser importados. Adicione os imports:

```go
import (
    "context"
    "crypto/rand"
    "errors"
    "fmt"
    "os"
    "time"
    ...
)

var cryptoRandRead = rand.Read
```

- [ ] **Step 2: Verificar compilação**

```bash
cd auth-service && go build ./...
```

Expected: sem erros.

- [ ] **Step 3: Commit**

```bash
git add auth-service/internal/service/
git commit -m "feat(auth): add JWT service with parallel validation via goroutines"
```

---

## Task 5: Handler layer e roteamento

**Files:**
- Create: `auth-service/internal/handler/auth_handler.go`
- Update: `auth-service/cmd/server/main.go`

- [ ] **Step 1: Criar auth_handler.go**

```go
// auth-service/internal/handler/auth_handler.go
package handler

import (
    "encoding/json"
    "errors"
    "net/http"
    "strings"

    "github.com/DevIcaro16/fintech-microservices-application/auth-service/internal/model"
    "github.com/DevIcaro16/fintech-microservices-application/auth-service/internal/service"
)

type AuthHandler struct {
    svc *service.AuthService
}

func NewAuthHandler(svc *service.AuthService) *AuthHandler {
    return &AuthHandler{svc: svc}
}

func (h *AuthHandler) Login(w http.ResponseWriter, r *http.Request) {
    var req model.LoginRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        jsonError(w, "invalid request body", http.StatusBadRequest)
        return
    }
    pair, err := h.svc.Login(r.Context(), req.Email, req.Password)
    if err != nil {
        if errors.Is(err, service.ErrInvalidCredentials) {
            jsonError(w, "invalid credentials", http.StatusUnauthorized)
            return
        }
        jsonError(w, "internal error", http.StatusInternalServerError)
        return
    }
    jsonOK(w, pair)
}

func (h *AuthHandler) Refresh(w http.ResponseWriter, r *http.Request) {
    var req model.RefreshRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        jsonError(w, "invalid request body", http.StatusBadRequest)
        return
    }
    pair, err := h.svc.Refresh(r.Context(), req.RefreshToken)
    if err != nil {
        jsonError(w, "invalid or expired refresh token", http.StatusUnauthorized)
        return
    }
    jsonOK(w, pair)
}

func (h *AuthHandler) Logout(w http.ResponseWriter, r *http.Request) {
    token := extractBearerToken(r)
    if token == "" {
        jsonError(w, "missing authorization header", http.StatusUnauthorized)
        return
    }
    if err := h.svc.Logout(r.Context(), token); err != nil {
        jsonError(w, "internal error", http.StatusInternalServerError)
        return
    }
    jsonOK(w, map[string]string{"message": "logged out"})
}

func (h *AuthHandler) Validate(w http.ResponseWriter, r *http.Request) {
    token := extractBearerToken(r)
    if token == "" {
        jsonError(w, "missing authorization header", http.StatusUnauthorized)
        return
    }
    resp, err := h.svc.ValidateParallel(r.Context(), token)
    if err != nil {
        jsonError(w, "token invalid or blacklisted", http.StatusUnauthorized)
        return
    }
    jsonOK(w, resp)
}

func extractBearerToken(r *http.Request) string {
    auth := r.Header.Get("Authorization")
    parts := strings.SplitN(auth, " ", 2)
    if len(parts) != 2 || parts[0] != "Bearer" {
        return ""
    }
    return parts[1]
}

func jsonOK(w http.ResponseWriter, v any) {
    w.Header().Set("Content-Type", "application/json")
    json.NewEncoder(w).Encode(v)
}

func jsonError(w http.ResponseWriter, msg string, code int) {
    w.Header().Set("Content-Type", "application/json")
    w.WriteHeader(code)
    json.NewEncoder(w).Encode(map[string]string{"error": msg})
}
```

- [ ] **Step 2: Atualizar main.go com wiring completo**

```go
// auth-service/cmd/server/main.go
package main

import (
    "context"
    "log"
    "net/http"
    "os"

    "github.com/DevIcaro16/fintech-microservices-application/auth-service/internal/handler"
    "github.com/DevIcaro16/fintech-microservices-application/auth-service/internal/repo"
    "github.com/DevIcaro16/fintech-microservices-application/auth-service/internal/service"
    "github.com/go-chi/chi/v5"
    "github.com/go-chi/chi/v5/middleware"
    "github.com/jackc/pgx/v5/pgxpool"
    "github.com/prometheus/client_golang/prometheus/promhttp"
    "github.com/redis/go-redis/v9"
)

func main() {
    ctx := context.Background()

    // PostgreSQL
    pgURL := os.Getenv("DATABASE_URL")
    if pgURL == "" {
        pgURL = "postgres://authuser:authpass123@auth-postgres:5432/authdb"
    }
    pool, err := pgxpool.New(ctx, pgURL)
    if err != nil {
        log.Fatalf("postgres connect: %v", err)
    }
    defer pool.Close()
    if err := pool.Ping(ctx); err != nil {
        log.Fatalf("postgres ping: %v", err)
    }

    // Redis Cluster
    redisAddrs := os.Getenv("REDIS_CLUSTER_ADDRS")
    if redisAddrs == "" {
        redisAddrs = "redis-cluster-0.redis-cluster.data.svc.cluster.local:6379,redis-cluster-1.redis-cluster.data.svc.cluster.local:6379,redis-cluster-2.redis-cluster.data.svc.cluster.local:6379"
    }
    rdb := redis.NewClusterClient(&redis.ClusterOptions{
        Addrs: splitAddrs(redisAddrs),
    })
    if err := rdb.Ping(ctx).Err(); err != nil {
        log.Fatalf("redis ping: %v", err)
    }

    // Wiring
    userRepo := repo.NewUserRepo(pool)
    tokenRepo := repo.NewTokenRepo(rdb)
    authSvc := service.NewAuthService(userRepo, tokenRepo)
    authHandler := handler.NewAuthHandler(authSvc)

    // Router
    r := chi.NewRouter()
    r.Use(middleware.Logger)
    r.Use(middleware.Recoverer)
    r.Use(middleware.RequestID)

    r.Get("/healthz", func(w http.ResponseWriter, _ *http.Request) {
        w.WriteHeader(http.StatusOK)
    })
    r.Handle("/metrics", promhttp.Handler())

    r.Route("/auth", func(r chi.Router) {
        r.Post("/login", authHandler.Login)
        r.Post("/refresh", authHandler.Refresh)
        r.Post("/logout", authHandler.Logout)
        r.Get("/validate", authHandler.Validate)
    })

    port := os.Getenv("PORT")
    if port == "" {
        port = "8080"
    }
    log.Printf("auth-service listening on :%s", port)
    log.Fatal(http.ListenAndServe(":"+port, r))
}

func splitAddrs(s string) []string {
    var addrs []string
    for _, a := range strings.Split(s, ",") {
        a = strings.TrimSpace(a)
        if a != "" {
            addrs = append(addrs, a)
        }
    }
    return addrs
}
```

Adicione `"strings"` nos imports.

- [ ] **Step 3: Verificar compilação**

```bash
cd auth-service && go build ./...
```

Expected: sem erros.

- [ ] **Step 4: Commit**

```bash
git add auth-service/internal/handler/ auth-service/cmd/
git commit -m "feat(auth): add HTTP handlers and Chi router wiring"
```

---

## Task 6: Dockerfile e build da imagem

**Files:**
- Create: `auth-service/Dockerfile`

- [ ] **Step 1: Criar Dockerfile multi-stage**

```dockerfile
# auth-service/Dockerfile
FROM golang:1.22-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o auth-service ./cmd/server

FROM gcr.io/distroless/static-debian12
COPY --from=builder /app/auth-service /auth-service
EXPOSE 8080
ENTRYPOINT ["/auth-service"]
```

- [ ] **Step 2: Build da imagem dentro do minikube**

```bash
eval $(minikube docker-env)
cd auth-service
docker build -t auth-service:latest .
```

Expected: `Successfully built <id>` e `Successfully tagged auth-service:latest`.

- [ ] **Step 3: Commit**

```bash
git add auth-service/Dockerfile
git commit -m "feat(auth): add multi-stage Dockerfile"
```

---

## Task 7: Kubernetes manifests e deploy

**Files:**
- Create: `auth-service/k8s/configmap.yaml`
- Create: `auth-service/k8s/deployment.yaml`
- Create: `auth-service/k8s/service.yaml`

- [ ] **Step 1: Criar configmap.yaml**

```yaml
# auth-service/k8s/configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: auth-service-config
  namespace: fintech
data:
  DATABASE_URL: "postgres://authuser:authpass123@auth-postgres:5432/authdb"
  REDIS_CLUSTER_ADDRS: "redis-cluster-0.redis-cluster.data.svc.cluster.local:6379,redis-cluster-1.redis-cluster.data.svc.cluster.local:6379,redis-cluster-2.redis-cluster.data.svc.cluster.local:6379"
  PORT: "8080"
---
apiVersion: v1
kind: Secret
metadata:
  name: auth-service-secret
  namespace: fintech
type: Opaque
stringData:
  JWT_SECRET: "dev-jwt-secret-change-in-prod"
```

- [ ] **Step 2: Criar deployment.yaml**

```yaml
# auth-service/k8s/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: auth-service
  namespace: fintech
  labels:
    app: auth-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: auth-service
  template:
    metadata:
      labels:
        app: auth-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/metrics"
    spec:
      containers:
        - name: auth-service
          image: auth-service:latest
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: auth-service-config
          env:
            - name: JWT_SECRET
              valueFrom:
                secretKeyRef:
                  name: auth-service-secret
                  key: JWT_SECRET
          readinessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /healthz
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 10
          resources:
            requests:
              cpu: 100m
              memory: 64Mi
            limits:
              cpu: 300m
              memory: 128Mi
```

- [ ] **Step 3: Criar service.yaml**

```yaml
# auth-service/k8s/service.yaml
apiVersion: v1
kind: Service
metadata:
  name: auth-service
  namespace: fintech
  labels:
    app: auth-service
spec:
  selector:
    app: auth-service
  ports:
    - name: http
      port: 80
      targetPort: 8080
  type: ClusterIP
```

- [ ] **Step 4: Aplicar manifests**

```bash
kubectl apply -f auth-service/k8s/
kubectl wait deployment auth-service -n fintech --for=condition=available --timeout=60s
```

Expected: `deployment.apps/auth-service condition met`

- [ ] **Step 5: Verificar pods rodando**

```bash
kubectl get pods -n fintech -l app=auth-service
```

Expected: 2 pods `Running 1/1`.

- [ ] **Step 6: Commit**

```bash
git add auth-service/k8s/
git commit -m "feat(auth): add Kubernetes deployment, service and configmap"
```

---

## Task 8: Validação end-to-end

- [ ] **Step 1: Port-forward para teste local**

```bash
kubectl port-forward svc/auth-service 8080:80 -n fintech &
```

- [ ] **Step 2: Criar usuário de teste direto no PostgreSQL**

```bash
kubectl exec -n fintech auth-postgres-0 -- psql -U authuser -d authdb -c \
  "INSERT INTO users (email, password_hash) VALUES ('test@fintech.local', '\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LPVmRm.y/qy');"
```

(O hash corresponde à senha `password123`)

- [ ] **Step 3: Testar login**

```bash
curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@fintech.local","password":"password123"}' | python3 -m json.tool
```

Expected:
```json
{
  "access_token": "<jwt>",
  "refresh_token": "<jwt>",
  "expires_in": 900
}
```

- [ ] **Step 4: Testar validate**

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@fintech.local","password":"password123"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -s http://localhost:8080/auth/validate \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

Expected:
```json
{
  "user_id": "<uuid>",
  "email": "test@fintech.local",
  "valid": true
}
```

- [ ] **Step 5: Testar logout + validate rejeitado**

```bash
curl -s -X POST http://localhost:8080/auth/logout \
  -H "Authorization: Bearer $TOKEN"

curl -s http://localhost:8080/auth/validate \
  -H "Authorization: Bearer $TOKEN"
```

Expected último curl: `{"error":"token invalid or blacklisted"}` com status 401.

- [ ] **Step 6: Verificar métricas**

```bash
curl -s http://localhost:8080/metrics | grep -E "go_|http_"
```

Expected: métricas Prometheus presentes.

- [ ] **Step 7: Commit final**

```bash
git add .
git commit -m "feat(auth): auth-service complete - JWT, blacklist, goroutine validation, K8s deploy"
```

---

## Critérios de aceite

- [ ] `POST /auth/login` retorna par de tokens JWT válidos
- [ ] `GET /auth/validate` valida assinatura e blacklist em paralelo (goroutines)
- [ ] `POST /auth/logout` coloca o access token na blacklist do Redis
- [ ] `POST /auth/refresh` rotaciona o par de tokens
- [ ] Pods em `Running` no namespace `fintech`
- [ ] `/healthz` retorna 200
- [ ] `/metrics` expõe métricas Prometheus
- [ ] PostgreSQL com tabelas `users` e `outbox` criadas
