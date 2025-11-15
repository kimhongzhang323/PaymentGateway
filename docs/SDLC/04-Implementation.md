# 4. Implementation

## 4.1 Code Layout
- payment-api/src/main/java/.../controller
- payment-core/src/main/java/.../service
- payment-domain/src/main/java/.../entity
- payment-common/src/main/java/.../util|security|constant

## 4.2 Build & Dependencies
- Spring Boot 3.5.7
- PostgreSQL driver
- Flyway
- Lombok

## 4.3 Configuration
- application.yml loads .env automatically
- JDBC URL uses sslmode=require for Supabase

## 4.4 Logging
- logback-spring.xml with rolling policies
- PaymentLogger utility for structured logs

