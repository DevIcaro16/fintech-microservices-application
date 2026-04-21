FROM oven/bun:1-alpine AS builder
WORKDIR /app
COPY package.json bun.lock* ./
RUN bun install --frozen-lockfile
COPY . .
RUN bun run build:client

FROM oven/bun:1-alpine
WORKDIR /app
COPY --from=builder /app .
EXPOSE 3002
CMD ["bun", "run", "src/server.tsx"]
