# KimPay Payment Gateway — Project Instructions

KimPay is a sandbox-grade, industry-shaped **payment gateway**: Java (see pom `<java.version>`), Spring Boot 3.5.x, Maven multi-module (`payment-api`, `payment-core`, `payment-domain`, `payment-common`), PostgreSQL + Flyway, Redis + Redisson, Kafka.

**This is a payment system. Money-correctness and security are the bar — when in doubt, choose the safer, more correct option and ask before guessing on anything touching funds or auth.**

## Working agreements
- Default to TDD. Run real tests and report real results — never claim green without running.
- Respect module boundaries and dependency direction (see architecture rules below).
- Use feature branches; never implement directly on `main`.
- Significant decisions go in `.claude/docs/decision-log.md`.

## Rules (read and follow)
@.claude/rules/coding-standards.md
@.claude/rules/architecture-principles.md
@.claude/rules/testing-strategy.md
@.claude/rules/security.md
@.claude/rules/api-design.md
@.claude/rules/performance.md

Backend persistence work also follows `@.claude/rules/path-scoped/backend/database.md`.

## Reference docs
- Architecture: `@.claude/docs/architecture.md` (deep dive: `ARCHITECTURE.md`)
- Tech stack & commands: `@.claude/docs/tech-stack.md`
- Glossary: `@.claude/docs/glossary.md`
- Decision log: `@.claude/docs/decision-log.md`

## Tooling
- **Skills** (`.claude/skills/`): implement-feature, fix-bug, write-tests, refactor, code-review, deploy.
- **Commands** (`.claude/commands/`): `/review`, `/triage`, `/hotfix`, `/release`.
- **Agents** (`.claude/agents/`): senior-engineer, security-auditor, tech-lead, qa-specialist.

## Current work
Roadmap, specs, and plans live in `docs/superpowers/`. Active: **Phase 1 — Security Foundation** (`docs/superpowers/plans/2026-05-25-phase1-security-foundation.md`).

## Common commands
- All tests: `./mvnw test`
- One class: `./mvnw -pl <module> -am test -Dtest=<Class>`
- Build: `./mvnw clean package`
- Local infra: `docker compose up`
