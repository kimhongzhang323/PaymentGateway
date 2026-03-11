# 50 Backend Engineer Intern Interview Questions & Answers (Digital Bank)

As a backend engineer intern at a digital bank, you are expected to have a solid grasp of core computer science fundamentals, backend frameworks, database management, and basic system design principles. This document provides 50 common interview questions with concise answers relevant to a FinTech backend role.

---

## ☕ Core Java & JVM

**1. Explain the difference between `String`, `StringBuilder`, and `StringBuffer` in Java.**
- **`String`**: Immutable. Operations that alter strings create new objects. Slow for heavy concatenation.
- **`StringBuilder`**: Mutable and not thread-safe. Faster than StringBuffer. Best for single-threaded string manipulations.
- **`StringBuffer`**: Mutable and thread-safe (synchronized methods). Best for multi-threaded environments where strings are shared.

**2. What is the difference between an `Interface` and an `Abstract Class` in Java 8+?**
- **Interface**: Can only have constants, method signatures, default methods, and static methods. A class can implement multiple interfaces.
- **Abstract Class**: Can have state (instance variables), constructors, and both abstract and non-abstract methods. A class can extend only one abstract class. Used when classes share core identity/state.

**3. How does Garbage Collection work in Java, and why is it important avoiding memory leaks in financial applications?**
- GC automatically reclaims memory by deleting objects that are no longer reachable. In FinTech, high-frequency transactions create many objects. Memory leaks (unintentionally holding strong references to unused objects) cause OutOfMemory errors, leading to application crashes and dropped transactions.

**4. Explain the `equals()` and `hashCode()` contract. Why is it critical to override both when using objects in a `HashMap`?**
- If two objects are equal according to `equals()`, they must have the same `hashCode()`. 
- If you override `equals()` without `hashCode()`, identical objects might be placed in different hash buckets, making them unretrievable from a `HashMap` or `HashSet`.

**5. What is the difference between Checked and Unchecked Exceptions? When would you use each?**
- **Checked (Compile-time)**: Must be caught or declared (e.g., `IOException`). Used for recoverable conditions outside the program's control.
- **Unchecked (Runtime)**: Extends `RuntimeException` (e.g., `NullPointerException`). Used for programming errors that usually shouldn't be caught but fixed in code.

**6. Explain the concepts of Autoboxing and Unboxing. What are the performance implications?**
- **Autoboxing**: Automatic conversion from primitive to wrapper (e.g., `int` to `Integer`).
- **Unboxing**: Wrapper to primitive.
- **Performance**: High volume autoboxing creates many temporary objects, triggering Frequent Garbage Collection. In loops/banking logic, prefer primitives for speed.

**7. What is the Streams API introduced in Java 8? How does it differ from Collections?**
- Streams process sequences of elements declaratively (map, filter, reduce). 
- Collections store data; Streams process data. Streams don't modify the underlying collection and can process data lazily or in parallel easily.

**8. Explain the concept of Thread Safety. Name a few thread-safe collections in Java.**
- Thread safety means multiple threads can access a piece of code/data concurrently without causing data corruption. 
- Examples: `ConcurrentHashMap`, `CopyOnWriteArrayList`, `BlockingQueue`.

**9. What is a `synchronized` block in Java? What are its drawbacks in high-performance applications?**
- It restricts access to a block of code to one thread at a time using an object's intrinsic lock.
- **Drawbacks**: Causes thread blocking and context switching, killing throughput (TPS) in concurrent banking apps. Alternative: `java.util.concurrent.locks` or atomic variables.

**10. Explain the difference between `==` and `.equals()` when comparing objects.**
- `==` compares object references (memory locations).
- `.equals()` compares the actual value or state of the objects (if specifically overridden).

---

## 🍃 Spring Boot & REST APIs

**11. What is Dependency Injection (DI) and Inversion of Control (IoC), and how does Spring Boot implement them?**
- **IoC**: The framework controls the flow and lifecycle of objects rather than the developer `new`ing them up.
- **DI**: The framework "injects" dependencies (components) into classes when needed. Spring uses annotations like `@Autowired` and the ApplicationContext to manage these Beans.

**12. Explain the difference between `@Controller` and `@RestController`.**
- `@Controller`: Used for returning views (HTML/JSP) in traditional MVC.
- `@RestController`: Combines `@Controller` and `@ResponseBody`. It returns data directly to the HTTP response body (usually JSON), which is standard for APIs.

**13. How does the `@Transactional` annotation work under the hood in Spring?**
- Spring uses AOP (Aspect-Oriented Programming) to wrap the method in a proxy. It opens a database transaction before the method runs and either commits it on success or rolls it back if a `RuntimeException` is thrown.

**14. What are the differences between HTTP `POST`, `PUT`, and `PATCH` methods?**
- `POST`: Create a new resource. Non-idempotent.
- `PUT`: Update an entire resource or create it if missing. Idempotent.
- `PATCH`: Partially update an existing resource.

**15. What is the purpose of the `@Autowired` annotation? What are the different types of injection?**
- It tells Spring to inject a matching bean. 
- Types: Constructor injection (recommended for immutability), Field injection (not recommended, hard to test), and Setter injection.

**16. How would you handle global exception handling in a Spring Boot application?**
- By using `@ControllerAdvice` or `@RestControllerAdvice` along with `@ExceptionHandler` methods to catch specific exceptions globally and return unified error JSON objects (e.g., standardizing HTTP 400 or 500 errors).

**17. What are Spring Boot Starters and how do they simplify dependency management?**
- They are pre-configured sets of dependencies you can include in your `pom.xml`. E.g., `spring-boot-starter-web` automatically pulls in Spring MVC, Jackson (JSON), and an embedded Tomcat server, ensuring version compatibility.

**18. Explain the difference between `@Component`, `@Service`, and `@Repository`.**
- `@Component`: Generic stereotype for any Spring-managed class.
- `@Service`: Specialized component for business logic layer.
- `@Repository`: Specialized component for data access layer. Also enables automatic translation of SQL exceptions to Spring's DataAccessException.

**19. What is a RESTful API? What are some best practices for designing one for a banking application?**
- Architecture style using standard HTTP methods and stateless communication.
- **Best Practices**: Use plural nouns (`/accounts`), versioning (`/v1/`), standard status codes, secure headers, pagination, and never expose internal DB IDs unnecessarily.

**20. How do you paginate and sort data effectively in a Spring Data JPA application?**
- By expanding repository method parameters to include a `Pageable` object (e.g., `PageRequest.of(page, size, sort)`). This pushes the `LIMIT` and `OFFSET` commands to the SQL level rather than pulling all records into memory.

---

## 🗄️ Relational Databases & PostgreSQL

**21. What are ACID properties? Why are they absolutely critical in a banking system?**
- **Atomicity**: All or nothing (either debit and credit fire, or neither).
- **Consistency**: DB remains in a valid state (no negative balances if constrained).
- **Isolation**: Concurrent transactions don't interfere (no double spending).
- **Durability**: Once committed, data is saved permanently even if power is lost.

**22. Explain the difference between an `INNER JOIN` and a `LEFT OUTER JOIN`.**
- `INNER JOIN`: Returns only rows with a match in both tables.
- `LEFT JOIN`: Returns all rows from the left table, and the matched rows from the right table. If no match, right side results are NULL.

**23. What is an Index in a database? How does it improve read performance, and what is the trade-off?**
- A data structure (usually B-Tree) that improves data retrieval speed.
- **Trade-off**: Increases disk space and severely slows down writes (INSERT/UPDATE), as indexes must be restructured on modification.

**24. Explain the concept of Database Normalization and Denormalization.**
- **Normalization**: Organizing data into multiple related tables to reduce redundancy and prevent update anomalies (e.g., 3rd Normal Form).
- **Denormalization**: Intentionally adding redundant data to a table to speed up heavily read queries by avoiding complex JOINs.

**25. What is the N+1 select problem in ORM frameworks like Hibernate, and how do you fix it?**
- **Problem**: Fetching 1 parent record and deeply associating N child records results in 1 + N SQL queries, destroying performance.
- **Fix**: Use `@EntityGraph` or `JOIN FETCH` in JPQL to grab parent and children in a single query.

**26. What is a Database Transaction? Explain the Isolation Levels.**
- A logical unit of work. 
- Levels: Read Uncommitted (dirty reads allowed) -> Read Committed -> Repeatable Read (prevents non-repeatable reads) -> Serializable (highest, prevents phantom reads, but heavily locks database).

**27. What is the difference between a Primary Key and a Unique Key?**
- **Primary Key**: Identifies a row uniquely. Cannot be null. Only one per table.
- **Unique Key**: Identifies a row uniquely. Can accept one NULL value (usually). Multiple unique keys allowed.

**28. Explain Pessimistic vs. Optimistic Locking. When would you use which in a wallet system?**
- **Optimistic**: Uses a version column. Fast, assumes no conflict. If rows conflict on save, it throws an exception. Good for low-collision systems.
- **Pessimistic**: Locks the row in the DB (`SELECT ... FOR UPDATE`). Slower but guarantees safety in high-collision areas (like simultaneous debit requests on a wallet). 

**29. What is Connection Pooling, and why do we use tools like HikariCP?**
- Establishing a database connection over a network is extremely slow. Connection pooling creates and maintains a pool of active DB connections ready to be checked out, reused, and returned by application threads, vastly improving TPS.

**30. How do you handle schema migrations in a production application? (Hint: Flyway/Liquibase)**
- You never write manual SQL in prod. Tools like `Flyway` run versioned `.sql` scripts (e.g., `V1__init.sql`) automatically on app startup. It tracks applied migrations in a metadata table to ensure consistent schema across environments.

---

## 🚀 Caching, Message Queues & Microservices

**31. Why would we use a cache like Redis in a payment gateway?**
- Redis operates completely in RAM (memory), responding in sub-milliseconds. It is used to quickly block duplicate API requests, manage rate limiting, or store session/merchant data to reduce expensive database queries.

**32. What is the concept of a Cache Miss and a Cache Eviction strategy (e.g., LRU)?**
- **Cache Miss**: Requesting data that isn't in the cache, requiring a slow database fallback.
- **Eviction Strategy**: When RAM is full, the cache drops old data. **LRU** (Least Recently Used) drops data that hasn't been accessed in the longest time.

**33. Explain the difference between synchronous and asynchronous communication between services.**
- **Synchronous**: Service A calls Service B and waits for the response (blocking). Example: REST HTTP call.
- **Asynchronous**: Service A drops a message in a queue and moves on (non-blocking). Service B processes it eventually. Example: Kafka Pub/Sub.

**34. What is Apache Kafka (or RabbitMQ)? Why use a message broker instead of direct API calls?**
- A distributed event streaming platform. Used to decouple microservices. If an Email Notification service goes down, direct API calls would fail and drop the email. A message broker stores the event safely until the Email service comes back online to process it.

**35. Explain the concept of a "Topic" and a "Consumer Group" in Kafka.**
- **Topic**: A categorized stream of data/events. Publishers write to topics.
- **Consumer Group**: A group of microservice instances reading from a topic. Kafka ensures each message goes to only ONE instance within a consumer group to prevent duplicate processing.

**36. What is Idempotency? Why must a payment processing endpoint be idempotent?**
- Idempotency guarantees that executing the same request 1 time or 10 times yields the identical system state. If a user suffers network lag and clicks "Pay" 3 times, an idempotent system ensures they are only charged once.

**37. What is a Distributed Lock, and why might we need it when running multiple instances of a Spring Boot app?**
- Java `synchronized` blocks only restrict threads inside a single JVM node. If you have 3 instances of an app running, they bypass each other. Distributed locks (via Redis/Redisson or Zookeeper) ensure isolation across the entire cluster of servers.

**38. Explain the concept of API Rate Limiting. Why is it important for a public-facing API?**
- Restricting the number of requests a user/IP can make in a time window. It prevents brute-force password guessing, limits scrape attacks, and stops Denial of Service (DoS) attacks from crashing the bank's servers.

**39. What is the difference between Monolithic and Microservices architectures? Key Pros and Cons?**
- **Monolith**: Single deployable unit. Easy to test/deploy initially. Becomes massive, hard to coordinate releases, and a bug in one piece crashes the whole app.
- **Microservices**: Split by business domain. Can scale independently (e.g., scale payments, but not user profile). Complex to monitor, test, and handle distributed transactions.

**40. How do Microservices communicate with each other securely and reliably?**
- REST APIs over HTTPS for synchronous, Kafka/SQS for asynchronous. 
- Using features like Retries, Exponential Backoff, Circuit Breakers (Resilience4j) for reliability, and mTLS or API Gateways for security.

---

## 🔐 General Computer Science, Security & DevOps

**41. What is the difference between Authentication and Authorization?**
- **Authentication**: Verifying WHO the user is (e.g., checking Username/Password).
- **Authorization**: Verifying WHAT the user is allowed to do (e.g., Admin vs Customer attempting to process a refund).

**42. Explain how JWT (JSON Web Tokens) work. Where should you store them securely?**
- A stateless token containing a base64 encoded payload and a cryptographic signature. The server verifies the signature hasn't been tampered with. It should be stored in `HttpOnly, Secure` cookies to prevent Cross-Site Scripting (XSS) attacks.

**43. What is a Race Condition? Give an example of how one might occur in a banking application.**
- Occurs when two concurrent threads access and modify shared data simultaneously, leading to unexpected outcomes. 
- Example: Wallet $100. Thread A & B read $100. Both subtract $100. Both save $0. The user successfully spent $200 from a $100 wallet.

**44. What is hashing? How is it different from encryption? (Hint: bcrypt vs. AES)**
- **Hashing**: A one-way mathematical function. Irreversible (e.g., Bcrypt for passwords). You can only compare identical hashes.
- **Encryption**: Two-way function relying on a key. Reversible (e.g., AES for bank account numbers).

**45. Explain what HTTPS and TLS/SSL do. What is a Man-In-The-Middle (MITM) attack?**
- **HTTPS/TLS**: Encrypts data in transit between the client browser and the server.
- **MITM**: An attacker secretly relays and intercepts communications intercepting passwords or tokens. TLS certificates prevent this by mathematically authenticating the server's identity.

**46. What are the principles of CI/CD (Continuous Integration and Continuous Deployment)?**
- **CI**: Developers frequently merge code into a central repository where automated builds and tests run to catch defects early.
- **CD**: Automated release of validated code to staging or production environments quickly and reliably.

**47. What is Docker? Why do we containerize applications?**
- Docker packages an application and all its dependencies (OS, Java, libs) into a standardized unit (Image/Container). It eliminates the "it works on my machine" problem setup, ensuring consistency across Dev, Test, and Prod stages.

**48. Explain the Model-View-Controller (MVC) design pattern.**
- **Model**: Represents the data and raw business logic.
- **View**: The UI presentation layer for the user.
- **Controller**: The brain that intercepts user requests, updates the model, and selects the view to render.

**49. What is a DDOS attack, and how do modern web applications defend against it?**
- **Distributed Denial of Service**: Flooding a server with fake traffic from thousands of infected computers (botnets) to crash it.
- **Defenses**: Edge firewalls (Cloudflare/AWS Shield), aggressive API rate limiting, auto-scaling clusters, and blackholing malicious IP blocks.

**50. Given a requirement to securely store a user's password, walk me through exactly how you would handle it from the API to the Database.**
- Accept password via POST request secured by TLS/HTTPS. 
- Read parameter, and NEVER log it to console.
- Pass to backend layer.
- Generate a unique "Salt" and combine it with the password.
- Hash it using an intentionally slow algorithm like `BCrypt` or `Argon2` (slows down brute-force hardware).
- Save ONLY the hash and the salt to the database. Upon login attempts, repeat and compare hashes.
