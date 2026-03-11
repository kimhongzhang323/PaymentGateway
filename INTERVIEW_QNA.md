# KimPay Payment Gateway - Interview & QnA Prep Guide

This document is designed to help you prepare for technical interviews, architectural reviews, or Q&A sessions regarding the **KimPay Payment Gateway**. It covers the most common and challenging questions an engineering manager, staff engineer, or technical interviewer might ask about this system.

---

## 🏗️ 1. Architecture & System Design

### Q1: Why did you choose a multi-module Maven architecture instead of a single monolithic Spring Boot app?
**A:** A multi-module architecture enforces strict separation of concerns and dependency rules at the build level. 
- **`payment-domain`** has zero external dependencies, keeping our core data models and enums pure. 
- **`payment-core`** depends on the domain but doesn't know about HTTP or REST. 
- **`payment-api`** handles web traffic but delegates all logic to the core. 
This prevents "spaghetti code" where controllers directly manipulate databases, makes the system easier to test, and enables us to break out `payment-core` into a standalone microservice later if needed without untangling web dependencies.

### Q2: How does the system ensure high availability and prevent the database from being the bottleneck?
**A:** We use a multi-tiered approach:
1. **Redis Caching (`StringRedisTemplate`):** High-read operations, like verifying if a merchant exists before processing a transaction, are offloaded to Redis with a 1-hour TTL. This drops DB queries by nearly 50%.
2. **Connection Pooling (`HikariCP`):** We strictly manage connection limits (max 10, min 5) to prevent connection exhaustion.
3. **Asynchronous Offloading (`Kafka`):** Only critical writes (like updating a balance and creating the `AUTHORIZED` transaction) happen synchronously. Logging, notifications, and analytics are fired into Kafka and processed asynchronously to keep the primary Tomcat HTTP thread pool free.

---

## 🔄 2. Concurrency & Race Conditions

### Q3: Imagine two users share a joint wallet and both hit "Pay" at the exact same millisecond. How do you prevent an overdraft?
**A:** We use a **Two-Layer Defense Mechanism** for concurrency:
1. **Application Layer (Fast):** We use **Redisson** to acquire a distributed lock on `payment:lock:wallet:{walletId}`. Since these requests might hit two different load-balanced Spring Boot instances, a JVM-level lock isn't enough. Redisson ensures only one node across the cluster can process this wallet's deduction at a given time.
2. **Database Layer (Bulletproof):** As an absolute fallback, our JPA repository uses pessimistic locking (`@Lock(LockModeType.PESSIMISTIC_WRITE)` which translates to `SELECT ... FOR UPDATE` in Postgres). Even if Redis goes down, the database level guarantees atomicity.

### Q4: What happens if a server crashes in the middle of a transaction while holding a Redisson lock?
**A:** This is why we don't use infinite locks. Our Redisson lock acquisition has a **strict 10-second lease time**. If the JVM crashes completely and never calls `.unlock()`, Redis will automatically expire and release the lock after 10 seconds, allowing the next transaction to proceed without deadlocking the user's wallet forever.

---

## 🛡️ 3. Safety, Idempotency, and Idempotency Keys

### Q5: What is idempotency and how did you implement it?
**A:** Idempotency ensures that if a client makes the exact same network request multiple times (due to a poor network dropping the response, or a user double-clicking "Submit"), the system only processes the charge *once*, but returns the same successful response each time.
- The client generates a unique UUID `idempotencyKey` and sends it in the request.
- We acquire a Redisson lock specific to that key to block rapid concurrent clicks from bypassing the check.
- We check Redis (`payment:idempotency:{key}`). If it exists, we fetch the previously saved transaction from the database and return it immediately without charging the user again.
- If it's new, we process the payment, save the transaction, and then write the key to Redis with a 24-hour TTL.

### Q6: Why check idempotency in Redis instead of just querying the database?
**A:** Speed and DB protection. In a high-TPS scenario (like a flash sale), if a bot sends 10,000 duplicated requests per second, querying the database array 10,000 times for `SELECT * FROM transactions WHERE idempotency_key = ?` will overwhelm Postgres and crash the platform. Redis checks take micro-seconds and keep the database completely shielded from duplicate storms.

---

## ✉️ 4. Event-Driven Messaging (Kafka)

### Q7: Why use Kafka instead of RabbitMQ or direct REST calls to downstream services?
**A:** Kafka provides exceptional throughput and, crucially, **durability and ordering**. 
- If we used direct REST calls to an Email Service and it was down, the payment API would hang or drop the email entirely. 
- Kafka acts as an immutable log. We publish events to a `payment.events` topic using the `transactionId` as the **message key**.
- Using the `transactionId` as the key guarantees that Kafka routes all events for that specific transaction (`AUTHORIZED` → `CAPTURED` → `REFUNDED`) into the **exact same partition**. This guarantees strictly ordered processing by consumers.

---

## 🔐 5. Security & Encryption

### Q8: How do you handle sensitive financial data like bank accounts or routing numbers?
**A:** All sensitive Personally Identifiable Information (PII) is encrypted at rest using **AES-256-GCM** via our `EncryptionService`.
- **GCM (Galois/Counter Mode):** Provides both confidentiality (encryption) and authenticity. If an attacker alters the encrypted string in the database, the decryption will fail rather than return garbled, potentially dangerous data.
- **Implementation:** We generate a unique, cryptographically secure 12-byte Initialization Vector (IV) for *every single database insert*. We use a JPA `AttributeConverter` (`EncryptedStringConverter`) so that the application manipulates plain Java strings, but the framework automatically encrypts them just before executing the SQL `INSERT`/`UPDATE` and decrypts them on `SELECT`.

### Q9: How does the QR payment system work securely?
**A:** Standard QR codes can be easily manipulated. If a merchant's QR code was just `merchantId:123|amount:50`, a malicious user could edit it to pay someone else.
Instead, we package the payload and encrypt the entire string using AES-256-GCM before generating the Base64 image. When the user scans it, the `payment-api` receives the encrypted blob, decrypts it using the internal secure key, ensures the payload hasn't been tampered with, and then securely extracts the merchant ID and amount to process the wallet deduction. 
