# Testing Strategy

## TDD is the default
Write the failing test first, watch it fail, write the minimal code to pass, refactor, commit. One behavior per test.

## Test pyramid
- **Unit tests** (most): pure logic — services with mocked repositories, ledger math, crypto, signature/HMAC verification, `SensitiveDataMasker`. Fast, no Spring context. Use Mockito.
- **Slice tests**: `@DataJpaTest` for repositories, `@WebMvcTest` for controller wiring where appropriate.
- **Integration tests** (fewer): `@SpringBootTest` + `MockMvc`. Today they run on H2 (`MODE=PostgreSQL`, `ddl-auto: create-drop`, Flyway disabled — see `application-test.yml`). For infrastructure-dependent paths (Redis nonce, Kafka), prefer **Testcontainers** going forward.
- **End-to-end** (few): full secured flow — real RSA keypair, signed request, asserted success + tampered-request rejection.

## Conventions
- Tests live under `src/test/java` mirroring the main package.
- Use AssertJ (`assertThat`) for assertions.
- Existing integration tests use `@AutoConfigureMockMvc(addFilters = false)` to bypass security. New security behavior must be tested with **filters enabled**.
- Clean DB state in `@BeforeEach` (delete in FK-safe order), as the existing integration test does.
- Never assert on internal exception messages that may change; assert on status codes and the `{ code }` envelope.

## What must be tested for payments
- Concurrency / idempotency: parallel double-spend on a wallet, duplicate idempotency keys, replayed nonces — assert zero overdraft / zero double charge.
- Money correctness: refunds never exceed remaining amount; ledger entries balance.
- Security: auth required, signature/replay rejection, validation 400s, no data leakage in error bodies.
- Failure paths: PSP/Redis unavailable → graceful degradation, not a 500 with a stack trace.

## Coverage gate
Target ≥ 80% line coverage on `payment-core`. Coverage is a floor, not the goal — cover behavior and edge cases, not getters.

## Running
- All modules: `./mvnw test`
- One module: `./mvnw -pl payment-api -am test`
- One class: `./mvnw -pl payment-core -am test -Dtest=ApiKeyServiceTest`
