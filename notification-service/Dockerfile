FROM oven/bun:1-alpine AS builder
WORKDIR /app
COPY package.json bun.lockb* ./
RUN bun install --frozen-lockfile
COPY . .

FROM oven/bun:1-alpine
WORKDIR /app
COPY --from=builder /app .
EXPOSE 3001
CMD ["bun", "run", "src/index.ts"]
