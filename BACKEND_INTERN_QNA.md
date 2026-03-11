# 50 Backend Engineer Intern Interview Questions (Digital Bank)

As a backend engineer intern at a digital bank, you are expected to have a solid grasp of core computer science fundamentals, backend frameworks, database management, and basic system design principles. This document provides 50 common interview questions across various domains relevant to a FinTech backend role.

---

## ☕ Core Java & JVM
1. **Explain the difference between `String`, `StringBuilder`, and `StringBuffer` in Java.**
2. **What is the difference between an `Interface` and an `Abstract Class` in Java 8+?**
3. **How does Garbage Collection work in Java, and why is it important avoiding memory leaks in financial applications?**
4. **Explain the `equals()` and `hashCode()` contract. Why is it critical to override both when using objects in a `HashMap`?**
5. **What is the difference between Checked and Unchecked Exceptions? When would you use each?**
6. **Explain the concepts of Autoboxing and Unboxing. What are the performance implications?**
7. **What is the Streams API introduced in Java 8? How does it differ from Collections?**
8. **Explain the concept of Thread Safety. Name a few thread-safe collections in Java.**
9. **What is a `synchronized` block in Java? What are its drawbacks in high-performance applications?**
10. **Explain the difference between `==` and `.equals()` when comparing objects.**

---

## 🍃 Spring Boot & REST APIs
11. **What is Dependency Injection (DI) and Inversion of Control (IoC), and how does Spring Boot implement them?**
12. **Explain the difference between `@Controller` and `@RestController`.**
13. **How does the `@Transactional` annotation work under the hood in Spring?**
14. **What are the differences between HTTP `POST`, `PUT`, and `PATCH` methods?**
15. **What is the purpose of the `@Autowired` annotation? What are the different types of injection (constructor vs. field)?**
16. **How would you handle global exception handling in a Spring Boot application? (Hint: `@ControllerAdvice`)**
17. **What are Spring Boot Starters and how do they simplify dependency management?**
18. **Explain the difference between `@Component`, `@Service`, and `@Repository`.**
19. **What is a RESTful API? What are some best practices for designing one for a banking application?**
20. **How do you paginate and sort data effectively in a Spring Data JPA application?**

---

## 🗄️ Relational Databases & PostgreSQL
21. **What are ACID properties? Why are they absolutely critical in a banking system?**
22. **Explain the difference between an `INNER JOIN` and a `LEFT OUTER JOIN`.**
23. **What is an Index in a database? How does it improve read performance, and what is the trade-off?**
24. **Explain the concept of Database Normalization and Denormalization.**
25. **What is the N+1 select problem in ORM frameworks like Hibernate, and how do you fix it?**
26. **What is a Database Transaction? Explain the Isolation Levels (Read Uncommitted, Read Committed, Repeatable Read, Serializable).**
27. **What is the difference between a Primary Key and a Unique Key?**
28. **Explain Pessimistic vs. Optimistic Locking. When would you use which in a wallet system?**
29. **What is Connection Pooling, and why do we use tools like HikariCP?**
30. **How do you handle schema migrations in a production application? (Hint: Flyway/Liquibase)**

---

## 🚀 Caching, Message Queues & Microservices
31. **Why would we use a cache like Redis in a payment gateway?**
32. **What is the concept of a Cache Miss and a Cache Eviction strategy (e.g., LRU)?**
33. **Explain the difference between synchronous and asynchronous communication between services.**
34. **What is Apache Kafka (or RabbitMQ)? Why use a message broker instead of direct API calls?**
35. **Explain the concept of a "Topic" and a "Consumer Group" in Kafka.**
36. **What is Idempotency? Why must a payment processing endpoint be idempotent?**
37. **What is a Distributed Lock, and why might we need it when running multiple instances of a Spring Boot app?**
38. **Explain the concept of API Rate Limiting. Why is it important for a public-facing API?**
39. **What is the difference between Monolithic and Microservices architectures? What are the pros and cons?**
40. **How do Microservices communicate with each other securely and reliably?**

---

## 🔐 General Computer Science, Security & DevOps
41. **What is the difference between Authentication and Authorization?**
42. **Explain how JWT (JSON Web Tokens) work. Where should you store them securely?**
43. **What is a Race Condition? Give an example of how one might occur in a banking application.**
44. **What is hashing? How is it different from encryption? (Hint: bcrypt vs. AES)**
45. **Explain what HTTPS and TLS/SSL do. What is a Man-In-The-Middle (MITM) attack?**
46. **What are the principles of CI/CD (Continuous Integration and Continuous Deployment)?**
47. **What is Docker? Why do we containerize applications?**
48. **Explain the Model-View-Controller (MVC) design pattern.**
49. **What is a DDOS attack, and how do modern web applications defend against it?**
50. **Given a requirement to securely store a user's password, walk me through exactly how you would handle it from the API to the Database.**
