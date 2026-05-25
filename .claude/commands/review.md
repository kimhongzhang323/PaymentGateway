---
description: Review the current branch diff against KimPay's code-review checklist
argument-hint: "[base branch, default: main]"
---

Review the changes on the current branch versus `${1:-main}`.

1. Run `git diff ${1:-main}...HEAD --stat` then inspect the full diff.
2. Apply the `.claude/skills/code-review` checklist: money correctness, concurrency, security, architecture/module hygiene, tests.
3. Cross-check against `.claude/rules/` (security.md, api-design.md, testing-strategy.md, architecture-principles.md).
4. Confirm `./mvnw test` passes; if you cannot run it, say so explicitly.

Output prioritized findings (Critical / Important / Nit) with `file:line` and concrete fixes, then a verdict: Approve / Approve with nits / Request changes. Do not modify code — review only.
