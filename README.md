# Tradewind

Robinhood-style paper-trading platform (simulated money and market data). See [spec.md](spec.md).
Phase 1 (core trading engine) is in progress.

## Prerequisites

- **JDK 21.** The build enforces it. On macOS with Homebrew:
  ```
  brew install openjdk@21
  export JAVA_HOME=$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home
  ```
- Maven 3.9+
- Docker (8 GB of memory for Docker recommended)

## Build and test

```
mvn verify
```

Integration tests start their own Postgres and Redis containers with Testcontainers, so Docker must be running.

## Run locally

```
cp .env.example .env                  # then set the passwords
docker compose up -d                  # Postgres, Kafka, Redis, Prometheus, Grafana
set -a; . ./.env; set +a
mvn -pl account-service spring-boot:run   # :8081
mvn -pl gateway spring-boot:run           # :8080, the only public entry point
```

| Service | URL |
|---|---|
| Gateway (public API) | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (credentials from `.env`) |
