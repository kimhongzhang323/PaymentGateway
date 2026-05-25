---
name: refactor
description: Use when restructuring KimPay code without changing behavior - enforces green-tests-before-and-after and respect for module boundaries.
---

# Refactor

Improve structure without changing behavior. Behavior change is a feature, not a refactor — do that separately.

## Preconditions
- Tests are green before you start (`./mvnw test`). If not, fix or characterize first — never refactor on red.
- There is adequate test coverage for the code you're touching. If not, add characterization tests first.

## Process
1. Make one structural change at a time (extract method, rename, move class to the correct module, split an oversized service).
2. Run the affected module's tests after each step. Keep them green continuously.
3. Commit each cohesive refactor separately with a `refactor:` message.

## KimPay-specific moves
- **Respect module direction** (`architecture-principles.md`): moving a class must not create an upward dependency (e.g. domain → core).
- Split large services by responsibility (payments vs refunds vs QR vs wallet), not by arbitrary line count.
- Replace unreliable patterns: `@ConditionalOnMissingBean` on a `@Component` → `@Bean` in a `@Configuration`.
- Extract magic values (TTLs, lock wait/lease, key prefixes) into named constants or config properties.
- Centralize duplicated logic (currency normalization, key prefixes) rather than copy-paste.

## Guardrails
- Do not change public API contracts, DB schema semantics, or error codes during a refactor.
- Do not reformat unrelated lines.
- If you discover a bug mid-refactor, stop, switch to `fix-bug` (test + fix), then resume.
