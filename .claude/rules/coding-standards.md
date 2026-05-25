# Coding Standards

Conventions for the KimPay codebase (Java 17, Spring Boot 3.5.x, Maven multi-module).

## Language & style
- Target the Java version declared in the parent `pom.xml` (`<java.version>`). Do not use features beyond it.
- Prefer **records** for DTOs and immutable value objects (see `core/dto`, `PaymentEvent`).
- Use **constructor injection** via Lombok `@RequiredArgsConstructor` + `final` fields. Never field injection (`@Autowired` on fields).
- Use `BigDecimal` for all monetary amounts — never `double`/`float`. Always specify scale/rounding explicitly when dividing.
- Use `java.time` (`LocalDateTime`, `Instant`) — never `java.util.Date`.

## Naming
- Services end in `Service`, controllers in `Controller`, repositories in `Repository`, JPA entities are nouns (`Transaction`, `Wallet`).
- Redis keys follow the existing namespaced convention: `payment:<domain>:<detail>` (e.g. `payment:lock:wallet:{id}`). Define prefixes as constants.

## Logging
- Use SLF4J (`@Slf4j` or `LoggerFactory`). Never `System.out`.
- **Never log** secrets, API keys, full PANs, private keys, or decrypted PII. Route sensitive strings through `SensitiveDataMasker`.
- Log at boundaries (request received, external call, failure). Avoid logging inside tight loops.

## Error handling
- Throw `IllegalArgumentException` for bad caller input (→ HTTP 400), `IllegalStateException` for invalid state transitions (→ HTTP 409). The global `ApiExceptionHandler` maps these.
- Never swallow exceptions silently. Never expose stack traces or internal messages to API clients — return the `{ code, message }` envelope.
- Re-interrupt the thread (`Thread.currentThread().interrupt()`) when catching `InterruptedException`.

## Comments
- Match the surrounding density. Explain *why*, not *what*. Keep the existing file header banners on new files in modules that use them.

## Formatting
- Follow existing import ordering and 4-space indentation. Do not reformat unrelated lines in a file you are editing.
