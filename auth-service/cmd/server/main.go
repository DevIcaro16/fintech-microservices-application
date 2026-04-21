package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"strings"

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

	userRepo := repo.NewUserRepo(pool)
	tokenRepo := repo.NewTokenRepo(rdb)
	authSvc := service.NewAuthService(userRepo, tokenRepo)
	authHandler := handler.NewAuthHandler(authSvc)

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
		if a = strings.TrimSpace(a); a != "" {
			addrs = append(addrs, a)
		}
	}
	return addrs
}
