# 1. Requirements

## 1.1 Business Goals
- Process card and wallet payments for merchants
- Support multi-currency transactions and settlements
- Provide APIs for checkout, webhooks, and reporting

## 1.2 Functional Requirements
- Create, authorize, capture, refund payments
- Manage merchants, users, roles, permissions
- Wallet top-up, spend, transfer
- Transaction logging, fees, settlements

## 1.3 Non-Functional Requirements
- Availability: 99.9% (prod)
- Latency: P95 API < 300ms (read), < 800ms (write)
- Security: PCI DSS alignment, AES-256 encryption
- Observability: tracing, metrics, logs
- Scalability: horizontal via stateless API + managed DB

## 1.4 Compliance & Risk
- PCI DSS, GDPR (data minimization), AML/KYC checks

## 1.5 Assumptions & Constraints
- Postgres via Supabase; Spring Boot 3.x; Java 17
- Migrations via Flyway; JPA for persistence

