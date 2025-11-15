# ✅ Supabase Setup Checklist

Use this checklist to track your Supabase setup progress.

## 📋 Pre-Setup

- [ ] Java 17+ installed
- [ ] Maven 3.6+ installed (or using wrapper)
- [ ] Git installed
- [ ] Text editor ready
- [ ] Supabase account created (https://supabase.com)

## 🗄️ Supabase Project Setup

- [ ] Created new Supabase project
- [ ] Named project: `kimpay-payment` (or your preference)
- [ ] Set strong database password (saved securely)
- [ ] Selected appropriate region
- [ ] Project provisioned (wait ~2 minutes)

## 🔑 Get Credentials

### Database Credentials
- [ ] Copied `SUPABASE_DB_HOST` from Settings → Database
- [ ] Copied `SUPABASE_DB_PORT` (default: 5432)
- [ ] Copied `SUPABASE_DB_USER` (default: postgres)
- [ ] Copied `SUPABASE_DB_PASSWORD` (what you set)

### API Credentials
- [ ] Copied `SUPABASE_URL` from Settings → API
- [ ] Copied `SUPABASE_ANON_KEY` from Settings → API
- [ ] Copied `SUPABASE_SERVICE_ROLE_KEY` from Settings → API (⚠️ keep secret!)
- [ ] Copied `SUPABASE_JWT_SECRET` from Settings → API → JWT Settings

## 🔧 Local Configuration

- [ ] Cloned/opened project in your editor
- [ ] Copied `.env.example` to `.env`
- [ ] Filled in all `SUPABASE_*` variables in `.env`
- [ ] Generated encryption key: `openssl rand -base64 32`
- [ ] Set `PAYMENT_ENCRYPTION_KEY_BASE64` in `.env`
- [ ] Verified `.env` is in `.gitignore`

## 🗃️ Database Schema

Choose ONE option:

### Option A: Manual Migration (Recommended for first time)
- [ ] Opened Supabase SQL Editor
- [ ] Copied `payment-api/src/main/resources/db/migration/V1__initial_schema.sql`
- [ ] Pasted into SQL Editor
- [ ] Clicked "Run"
- [ ] Verified 27 tables created in Table Editor

### Option B: Automatic Migration (Using Flyway)
- [ ] Set `FLYWAY_ENABLED=true` in `.env`
- [ ] Will auto-migrate when app starts

## 🏗️ Build & Run

- [ ] Ran `mvnw clean install`
- [ ] Build succeeded (no errors)
- [ ] Started app: `mvnw -pl payment-api spring-boot:run`
- [ ] App started successfully
- [ ] Saw log: "✓ Supabase configured"
- [ ] Saw log: "✓ Supabase database connection successful"

## 🧪 Testing

- [ ] Tested: `curl http://localhost:8080/api/health/ping`
- [ ] Response: `{"status":"ok"}`
- [ ] Tested: `curl http://localhost:8080/api/health/supabase`
- [ ] Response shows `"connected": true`
- [ ] Response shows correct database version
- [ ] Response shows tables exist

## 🔍 Verification

- [ ] Checked Supabase Table Editor
- [ ] Verified all 27 tables exist
- [ ] Verified initial data seeded (roles, permissions, currencies)
- [ ] Can query tables in SQL Editor
- [ ] No errors in application logs
- [ ] No errors in Supabase logs

## 📊 Database Tables Verification

### User Management (6 tables)
- [ ] `users`
- [ ] `roles`
- [ ] `permissions`
- [ ] `role_permissions`
- [ ] `user_sessions`
- [ ] `merchants`

### Payment Infrastructure (5 tables)
- [ ] `payment_methods`
- [ ] `wallets`
- [ ] `wallet_transactions`
- [ ] `merchant_settings`
- [ ] `cards`

### Transactions (4 tables)
- [ ] `transactions`
- [ ] `transaction_logs`
- [ ] `transaction_fees`
- [ ] `refunds`

### Security (3 tables)
- [ ] `card_tokens`
- [ ] `fraud_alerts`
- [ ] `otp_verifications`

### Banking (4 tables)
- [ ] `bank_accounts`
- [ ] `acquirers`
- [ ] `settlements`
- [ ] `payment_gateway_logs`

### Currency (3 tables)
- [ ] `currencies`
- [ ] `exchange_rates`
- [ ] `fees`

### Analytics (2 tables)
- [ ] `reports`
- [ ] `audit_logs`

## 🔐 Security Setup (Production)

- [ ] Reviewed security checklist in `docs/SUPABASE_SETUP.md`
- [ ] Planned Row Level Security (RLS) policies
- [ ] Configured IP whitelisting (if needed)
- [ ] Set up monitoring/alerting
- [ ] Configured backup strategy
- [ ] Environment variables stored securely
- [ ] `.env` file NOT committed to git

## 📚 Documentation Review

- [ ] Read `SETUP_COMPLETE.md` - Next steps guide
- [ ] Read `docs/SUPABASE_SETUP.md` - Detailed setup
- [ ] Read `SUPABASE_README.md` - Quick reference
- [ ] Reviewed `ERD.puml` - Database schema
- [ ] Bookmarked `docs/` folder for reference

## 🎯 Ready for Development

- [ ] Project builds successfully
- [ ] Database connection working
- [ ] All tables created
- [ ] Health endpoints responding
- [ ] Environment configured
- [ ] Ready to create repositories
- [ ] Ready to build services
- [ ] Ready to add controllers

## 🚀 Optional Enhancements

- [ ] Set up Supabase Realtime for transaction monitoring
- [ ] Configure Supabase Auth for authentication
- [ ] Set up Supabase Storage (if needed for files)
- [ ] Enable Point-in-Time Recovery (PITR) for backups
- [ ] Configure custom domain
- [ ] Set up staging environment

## 📝 Notes

Write any issues or special configurations here:

```
[Your notes here]




```

---

## ✅ Completion Status

**Date Started**: _______________

**Date Completed**: _______________

**Setup By**: _______________

**Status**: 
- [ ] In Progress
- [ ] Complete
- [ ] Blocked (see notes)

---

**Total Checkboxes**: 90+
**Time to Complete**: ~20-30 minutes (first time)

Once all checkboxes are checked, you're ready to start building features! 🎉

