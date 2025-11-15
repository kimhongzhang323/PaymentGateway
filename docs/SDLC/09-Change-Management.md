# 9. Change Management

## 9.1 Branching Strategy
- main: stable
- feature/*, fix/* branches; PRs required

## 9.2 Code Review
- At least 1 approval; CI green before merge

## 9.3 Versioning & Releases
- Semantic versioning for artifacts
- Release notes generated from commits

## 9.4 Migrations
- Flyway-based; each change in a new migration file

