package service

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
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
	if ttl > 0 {
		if err := s.tokens.BlacklistToken(ctx, claims.ID, ttl); err != nil {
			return err
		}
	}
	return s.tokens.DeleteRefreshToken(ctx, claims.UserID)
}

// ValidateParallel valida assinatura JWT e consulta blacklist em goroutines paralelas.
func (s *AuthService) ValidateParallel(ctx context.Context, accessToken string) (*model.ValidateResponse, error) {
	type claimsResult struct {
		claims *AuthClaims
		err    error
	}
	type blacklistResult struct {
		blacklisted bool
		err         error
	}

	claimsCh := make(chan claimsResult, 1)
	blacklistCh := make(chan blacklistResult, 1)

	go func() {
		claims, err := s.parseToken(accessToken)
		claimsCh <- claimsResult{claims: claims, err: err}
	}()

	jti := extractJTI(accessToken)
	go func() {
		if jti == "" {
			blacklistCh <- blacklistResult{}
			return
		}
		bl, err := s.tokens.IsBlacklisted(ctx, jti)
		blacklistCh <- blacklistResult{blacklisted: bl, err: err}
	}()

	cr := <-claimsCh
	br := <-blacklistCh

	if cr.err != nil {
		return nil, ErrInvalidToken
	}
	if br.err != nil {
		return nil, br.err
	}
	if br.blacklisted {
		return nil, ErrTokenBlacklisted
	}
	return &model.ValidateResponse{
		UserID: cr.claims.UserID,
		Email:  cr.claims.Email,
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
	claims := &AuthClaims{}
	p := jwt.NewParser()
	_, _, _ = p.ParseUnverified(tokenStr, claims)
	return claims.ID
}

func newJTI() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%08x-%04x-%04x-%04x-%12x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:])
}
