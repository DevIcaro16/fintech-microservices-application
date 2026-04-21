FROM oven/bun:1.1-alpine AS builder
WORKDIR /app
COPY package.json bun.lock* ./
RUN bun install
COPY . .

FROM oven/bun:1.1-alpine
WORKDIR /app
COPY --from=builder /app .
EXPOSE 3000
CMD ["bun", "run", "src/index.ts"]
