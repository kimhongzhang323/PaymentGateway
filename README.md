# KimPay Payment Gateway

> A production-grade, multi-module payment gateway system built with Spring Boot 3.5, PostgreSQL, Redis, and Kafka.

[![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.7-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-15-blue?logo=postgresql)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-7-red?logo=redis)](https://redis.io/)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-7.4-black?logo=apachekafka)](https://kafka.apache.org/)
[![License](https://img.shields.io/badge/License-Proprietary-lightgrey)](LICENSE)

---

## Table of Contents

- [Overview](#-overview)
- [Architecture](#-architecture)
- [Features](#-features)
- [Technology Stack](#-technology-stack)
- [Project Structure](#-project-structure)
- [Getting Started](#-getting-started)
  - [Prerequisites](#prerequisites)
  - [Option A: Local Docker (Recommended)](#option-a-local-docker-recommended)
  - [Option B: Cloud Supabase](#option-b-cloud-supabase)
- [Configuration](#-configuration)
- [API Reference](#-api-reference)
- [Database Schema](#-database-schema)
- [Security](#-security)
- [Development](#-development)
- [Testing](#-testing)
- [Deployment](#-deployment)
- [License](#-license)

---

## 📖 Overview

KimPay is a comprehensive, enterprise-grade payment gateway designed to handle the full lifecycle of financial transactions — from authorization and capture to partial refunds and QR-code payments. It is built with a clean multi-module Maven architecture, separating concerns across domain modeling, core business logic, shared utilities, and the API layer.

Key design principles:
- **Idempotency** — Distributed Redis-backed idempotency checks prevent duplicate payment processing
- **Race-condition safety** — Redisson distributed locks protect wallet debits across multiple nodes
- **Event-driven** — Kafka publishes payment lifecycle events for downstream consumers
- **Security-first** — AES-256-GCM encryption for all sensitive data at rest
- **Cloud-ready** — Native Supabase integration with Flyway-managed schema migrations

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────┐
│                    payment-api                      │
│  Spring Boot App │ REST Controllers │ Security      │
└───────────┬─────────────────────────────────────────┘
            │ depends on
┌───────────▼─────────────────────────────────────────┐
│                   payment-core                      │
│  PaymentService │ QRService │ Repositories          │
│  Redis Cache │ Redisson Locks │ Kafka Events        │
└───────────┬─────────────────────────────────────────┘
            │ depends on
┌──────┬────▼──────────────────────────────────┐
│      │           payment-domain              │
│      │  JPA Entities │ Status Enums          │
│      └───────────────────────────────────────┘
│                   │
│      ┌────────────▼──────────────────────────┐
│      │         payment-common                │
│      │  EncryptionService │ QR Utilities     │
└──────┴────────────────────────────────────────┘

Infrastructure:
  PostgreSQL (port 5432) ─── Primary datastore
  Redis       (port 6379) ─── Caching & distributed locks
  Kafka       (port 9092) ─── Payment event streaming
```

---

## ✨ Features

| Category | Feature |
|---|---|
| **Payments** | Create, capture, void, and refund transactions |
| **QR Payments** | Generate encrypted merchant QR codes; process QR scans |
| **Idempotency** | Redis-backed duplicate prevention with distributed locking |
| **Wallets** | Wallet debit/credit with pessimistic DB locking + Redisson |
| **Merchant Caching** | Redis cache for high-TPS merchant existence checks |
| **Event Streaming** | Kafka payment lifecycle events (AUTHORIZED, CAPTURED, FAILED, REFUNDED) |
| **Encryption** | AES-256-GCM for all sensitive fields via `EncryptionService` |
| **Multi-Currency** | ISO 4217 currency normalization and validation |
| **Partial Refunds** | Cumulative refund tracking on CAPTURED transactions |
| **Audit Trail** | Immutable `TransactionLog` entries for every payment state change |
| **Schema Management** | Flyway migrations with full initial schema and data seeding |

---

## 🛠️ Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.5.7 |
| API | Spring MVC (REST) |
| Security | Spring Security, AES-256-GCM |
| Database | PostgreSQL 15 (Docker or Supabase cloud) |
| ORM | Hibernate / Spring Data JPA |
| Migrations | Flyway |
| Cache | Redis 7 (via Spring Data Redis + Lettuce) |
| Distributed Locks | Redisson 3.45 |
| Events | Apache Kafka (Confluent 7.4) |
| QR Codes | ZXing 3.5.3 |
| Build | Maven 3.9+ (Maven Wrapper included) |
| CI/CD | GitHub Actions |
| Containerization | Docker |

---

## 📁 Project Structure

```
PaymentGateway/
├── payment-api/            # Spring Boot app — REST layer, config, entry point
│   ├── src/main/java/com/kimpay/payment/
│   │   ├── PaymentApplication.java
│   │   ├── controller/
│   │   │   ├── PaymentController.java
│   │   │   └── SupabaseHealthController.java
│   │   ├── config/
│   │   │   ├── KafkaConfig.java
│   │   │   ├── RedisConfig.java
│   │   │   └── SupabaseConfig.java
│   │   └── event/
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/V1__initial_schema.sql
│
├── payment-core/           # Business logic, repositories, services
│   └── src/main/java/com/kimpay/payment/core/
│       ├── service/
│       │   ├── PaymentService.java
│       │   └── QRService.java
│       ├── repository/
│       ├── dto/
│       └── event/
│
├── payment-domain/         # JPA entities and status enums
│   └── src/main/java/com/kimpay/payment/domain/
│       └── entity/         # 18 entities (Transaction, Wallet, User, ...)
│
├── payment-common/         # Shared utilities (encryption, security, converters)
│   └── src/main/java/com/kimpay/payment/
│       ├── security/
│       │   ├── EncryptionService.java
│       │   ├── EncryptedStringConverter.java
│       │   └── EncryptionConfig.java
│       └── config/
│
├── docker-compose.yml      # Local infrastructure (Postgres, Redis, Kafka, Zookeeper)
├── run.ps1                 # One-command startup script (PowerShell)
├── run.bat                 # One-command startup script (Command Prompt)
├── mvnw / mvnw.cmd         # Maven Wrapper
└── pom.xml                 # Root POM (multi-module)
```

---

## 🚀 Getting Started

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Java (JDK) | 21+ | Required to build and run the app |
| Docker Desktop | Latest | Required for local infrastructure |
| Maven | 3.9+ | Or use the included `mvnw` / `mvnw.cmd` wrapper |

---

### Option A: Local Docker (Recommended)

This spins up **PostgreSQL, Redis, and Kafka** locally via Docker, then builds and starts the application — all in one step.

> **Note:** PostgreSQL is mapped to host port **5432**. Ensure your local port 5432 is available.

**PowerShell (recommended on Windows):**
```powershell
.\run.ps1
```

**Command Prompt:**
```cmd
run.bat
```

**What the script does:**
1. Starts Docker containers (`docker-compose up -d`)
2. Waits 10 seconds for services to initialize
3. Builds the project (`mvnw.cmd clean package -DskipTests`)
4. Sets environment variables (DB URL, Redis host, Kafka bootstrap servers, encryption key)
5. Launches the application JAR

**Manual step-by-step alternative:**
```powershell
# 1. Start infrastructure
docker-compose up -d

# 2. Build (skip tests)
.\mvnw.cmd clean package -DskipTests

# 3. Set environment variables
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/payment_gateway"
$env:SPRING_DATASOURCE_USERNAME="postgres"
$env:SPRING_DATASOURCE_PASSWORD="admin"
$env:REDIS_HOST="localhost"
$env:KAFKA_BOOTSTRAP_SERVERS="localhost:9092"
$env:PAYMENT_ENCRYPTION_KEY_BASE64="YXNkZmFzZGZhc2RmYXNkZmFzZGZhc2RmYXNkZmFzZGY="

# 4. Run
java -jar payment-api/target/payment-api-0.0.1-SNAPSHOT.jar
```

**Verify the app is running:**
```bash
curl http://localhost:8080/api/health/ping
```

---

### Option B: Cloud Supabase

Connect to a hosted Supabase PostgreSQL database instead of running it locally.

**1. Copy environment template:**
```bash
cp .env.example .env
```

**2. Edit `.env` with your Supabase credentials:**
```env
# Supabase Database (Direct Connection)
SUPABASE_DB_HOST=db.your-project-ref.supabase.co
SUPABASE_DB_PORT=5432
SUPABASE_DB_NAME=postgres
SUPABASE_DB_USER=postgres
SUPABASE_DB_PASSWORD=your-db-password

# Supabase API
SUPABASE_URL=https://your-project-ref.supabase.co
SUPABASE_ANON_KEY=your-anon-key
SUPABASE_SERVICE_ROLE_KEY=your-service-role-key

# Encryption (generate a 32-byte Base64 key)
PAYMENT_ENCRYPTION_KEY_BASE64=your-base64-encoded-32-byte-key
```

**3. Build and run:**
```powershell
.\mvnw.cmd clean package -DskipTests
java -jar payment-api/target/payment-api-0.0.1-SNAPSHOT.jar
```

---

## ⚙️ Configuration

All configuration is managed through `payment-api/src/main/resources/application.yml`, with values driven by environment variables.

| Environment Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | Supabase cloud URL | JDBC connection string |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | _(empty)_ | Database password |
| `REDIS_HOST` | `localhost` | Redis hostname |
| `REDIS_PORT` | `6379` | Redis port |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker address |
| `PAYMENT_ENCRYPTION_KEY_BASE64` | _(empty)_ | Base64-encoded 32-byte AES key |
| `FLYWAY_ENABLED` | `true` | Enable/disable Flyway migrations |
| `PORT` | `8080` | HTTP server port |
| `SHOW_SQL` | `false` | Log SQL queries |

---

## 📡 API Reference

Base URL: `http://localhost:8080`

### Payments — `/api/payments`

| Method | Endpoint | Description |
|---|---|---|
| `POST` | `/api/payments` | Create and authorize a payment |
| `GET` | `/api/payments/{transactionId}` | Get a payment by ID |
| `POST` | `/api/payments/{transactionId}/capture` | Manually capture an authorized payment |
| `POST` | `/api/payments/{transactionId}/void` | Void an authorized payment |
| `POST` | `/api/payments/{transactionId}/refund` | Refund a captured payment (partial supported) |
| `GET` | `/api/payments/user/{userId}` | List all payments for a user |
| `GET` | `/api/payments/merchant/{merchantId}` | List all payments for a merchant |

### QR Payments

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/payments/merchant/{merchantId}/qr?amount=&currency=` | Generate encrypted merchant QR code (Base64 image) |
| `POST` | `/api/payments/scan` | Process a QR code scan and execute payment |

### Health

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/health/ping` | Basic liveness check |
| `GET` | `/api/health/supabase` | Database connectivity check |

### Example: Create Payment

```json
POST /api/payments
{
  "userId": 1,
  "merchantId": 2,
  "paymentMethodId": 3,
  "walletId": null,
  "amount": 99.99,
  "currency": "USD",
  "capture": true,
  "idempotencyKey": "order-abc-123"
}
```

---

## 🗄️ Database Schema

The system manages **27 tables** via a single Flyway migration (`V1__initial_schema.sql`), organized into 7 domains:

| Domain | Tables |
|---|---|
| **User Management** | `users`, `roles`, `permissions`, `role_permissions`, `user_sessions`, `merchant_settings` |
| **Merchants** | `merchants` |
| **Payment Infrastructure** | `payment_methods`, `wallets`, `wallet_transactions`, `cards`, `card_tokens` |
| **Transactions** | `transactions`, `transaction_logs`, `transaction_fees`, `refunds` |
| **Security & Fraud** | `fraud_alerts`, `otp_verifications` |
| **Banking & Settlement** | `bank_accounts`, `acquirers`, `settlements`, `payment_gateway_logs` |
| **Currency & Fees** | `currencies`, `exchange_rates`, `fees` |
| **Analytics** | `reports`, `audit_logs` |

**Default seed data** is inserted on first migration:
- Roles: `admin`, `merchant`, `customer`
- Permissions: `manage_users`, `process_payments`, `view_reports`, `manage_settings`, `refund_transactions`
- Currencies: USD, EUR, GBP, PHP

Performance indexes are created on all high-cardinality foreign keys and frequently-queried columns (`email`, `status`, `created_at`).

---

## 🔐 Security

| Mechanism | Implementation |
|---|---|
| **Data Encryption** | AES-256-GCM (`EncryptionService`) — applied to bank account numbers, acquirer credentials |
| **Distributed Locks** | Redisson locks (`payment:lock:wallet:{id}`) prevent concurrent wallet debit race conditions |
| **Idempotency** | Redis keys (`payment:idempotency:{key}`) with 24-hour TTL prevent duplicate charges |
| **Merchant Caching** | Redis keys (`payment:merchant:exists:{id}`) with 1-hour TTL reduce DB load at high TPS |
| **Authentication** | Spring Security (JWT-based, configurable) |
| **Schema Isolation** | Supabase Row Level Security (RLS) policies available for production hardening |

> ⚠️ The demo encryption key in `run.ps1` / `run.bat` is for **local development only**. Always supply a securely generated 32-byte random key via environment variable in production.

**Generate a production-safe key:**
```bash
openssl rand -base64 32
```

---

## 🔧 Development

### Module Dependency Tree

```
payment-api
  ├── payment-core
  │     ├── payment-domain
  │     └── payment-common
  └── payment-common
```

### Build Commands

```powershell
# Full build (all modules)
.\mvnw.cmd clean install

# Build without tests
.\mvnw.cmd clean package -DskipTests

# Build a specific module
.\mvnw.cmd clean install -pl payment-common -am

# Compile only
.\mvnw.cmd clean compile -DskipTests
```

### Docker Infrastructure Management

```powershell
# Start all services
docker-compose up -d

# Stop all services (preserve data volumes)
docker-compose stop

# Stop and remove containers + volumes (clean slate)
docker-compose down -v

# View logs for a specific service
docker logs kimpay-postgres
docker logs kimpay-redis
docker logs kimpay-kafka
```

### Port Reference (Local Docker)

| Service | Container Port | Host Port |
|---|---|---|
| PostgreSQL | 5432 | **5432** |
| Redis | 6379 | 6379 |
| Kafka | 9092 | 9092 |
| Zookeeper | 2181 | _(internal only)_ |
| Application | 8080 | 8080 |

---

## 🧪 Testing

```powershell
# Run all tests
.\mvnw.cmd test

# Run tests for a specific module
.\mvnw.cmd test -pl payment-api

# Run with coverage report
.\mvnw.cmd clean verify

# Skip tests during build
.\mvnw.cmd clean package -DskipTests
```

---

# CI/CD Pipeline

The project includes a fully automated CI/CD pipeline powered by **GitHub Actions**.

### Workflow: `Payment Gateway CI/CD`
- **Build & Test**: Every push or Pull Request to the `main` branch triggers a full Maven build and execution of all unit/integration tests.
- **Dockerization**: For every push to `main`, a Docker image for `payment-api` is automatically built.
- **Artifacts**: Maven build artifacts (JARs) are uploaded and stored for each successful build on `main`.

**Local Docker Build:**
```bash
docker build -t kimpay/payment-api:latest -f payment-api/Dockerfile .
```

---

## 🚢 Deployment

### Build Production JAR

```powershell
.\mvnw.cmd clean package -DskipTests
# Output: payment-api/target/payment-api-0.0.1-SNAPSHOT.jar
```

### Run Production JAR

```bash
java -jar payment-api/target/payment-api-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=production
```

Ensure the following environment variables are set in your production environment:
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_PASSWORD`
- `REDIS_HOST`
- `KAFKA_BOOTSTRAP_SERVERS`
- `PAYMENT_ENCRYPTION_KEY_BASE64`

---

## 📊 Project Status

| Feature | Status |
|---|---|
| Multi-module Maven structure | ✅ Complete |
| PostgreSQL schema (27 tables, Flyway) | ✅ Complete |
| JPA entities with audit base classes | ✅ Complete |
| Payment CRUD (create, capture, void, refund) | ✅ Complete |
| QR payment generation and processing | ✅ Complete |
| Redis idempotency & merchant caching | ✅ Complete |
| Redisson distributed wallet locks | ✅ Complete |
| Kafka payment event publishing | ✅ Complete |
| AES-256-GCM encryption | ✅ Complete |
| Flyway migrations | ✅ Complete |
| Supabase cloud integration | ✅ Complete |
| Docker local development setup | ✅ Complete |
| GitHub Actions CI/CD pipeline | ✅ Complete |
| Authentication endpoints | 🔄 In Progress |
| Swagger / OpenAPI documentation | 📋 Planned |
| Integration tests | 📋 Planned |

---

## 📝 License

© 2025 Kimpay Technologies. All Rights Reserved.

Unauthorized copying, modification, distribution, or disclosure of this project, via any medium, is strictly prohibited. This project contains proprietary and confidential information.

---

**Built with ❤️ by KimPay Technologies**
