# 6. Deployment

## 6.1 Environments
- Local, Staging, Production

## 6.2 Configuration
- Environment variables via .env for local
- Secrets via Secret Manager in prod

## 6.3 Database Migrations
- Flyway on startup or CI/CD job per environment

## 6.4 Health & Readiness
- /api/health/ping
- /api/health/supabase

