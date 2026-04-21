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

func (r *TokenRepo) SaveRefreshToken(ctx context.Context, userID, token string, ttl time.Duration) error {
	return r.rdb.Set(ctx, "refresh:"+userID, token, ttl).Err()
}

func (r *TokenRepo) GetRefreshToken(ctx context.Context, userID string) (string, error) {
	return r.rdb.Get(ctx, "refresh:"+userID).Result()
}

func (r *TokenRepo) DeleteRefreshToken(ctx context.Context, userID string) error {
	return r.rdb.Del(ctx, "refresh:"+userID).Err()
}
