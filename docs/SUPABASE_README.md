# KimPay Payment Gateway - Quick Start with Supabase

## 🚀 Quick Setup (5 minutes)

### 1. Create Supabase Project
```bash
# Go to https://supabase.com/dashboard
# Click "New Project" and note your credentials
```

### 2. Configure Environment
```bash
# Copy and edit the environment file
cp .env.example .env
# Edit .env with your Supabase credentials
```

### 3. Run the Application
```bash
# Build and run
mvnw clean install
mvnw -pl payment-api spring-boot:run
```

### 4. Verify Setup
```bash
# Check logs for:
# ✓ Supabase configured
# ✓ Supabase database connection successful
```

## 📚 Documentation

- **[Supabase Setup Guide](docs/SUPABASE_SETUP.md)** - Detailed setup instructions
- **[ERD Diagram](ERD.puml)** - Database schema
- **[API Documentation](docs/INDEX.md)** - API reference

## 🔐 Environment Variables Required

```env
SUPABASE_DB_HOST=db.your-project.supabase.co
SUPABASE_DB_PASSWORD=your-password
SUPABASE_URL=https://your-project.supabase.co
SUPABASE_ANON_KEY=your-anon-key
SUPABASE_SERVICE_ROLE_KEY=your-service-key
PAYMENT_ENCRYPTION_KEY_BASE64=your-encryption-key
```

## 📦 Project Structure

```
payment/
├── payment-api/          # Spring Boot REST API
├── payment-domain/       # JPA Entities
├── payment-core/         # Business Logic
├── payment-common/       # Shared Utilities
└── docs/                 # Documentation
```

## 🛠️ Tech Stack

- **Backend**: Spring Boot 3.5.7, Java 17
- **Database**: Supabase (PostgreSQL)
- **Security**: Spring Security, AES-256 Encryption
- **Migrations**: Flyway
- **Build**: Maven

## 📝 Next Steps

1. Read the [Supabase Setup Guide](docs/SUPABASE_SETUP.md)
2. Review the [ERD diagram](ERD.puml) for database structure
3. Configure Row Level Security (RLS) for production
4. Implement authentication endpoints
5. Set up monitoring and logging

## ⚠️ Security Notes

- Never commit `.env` files
- Use `service_role_key` only server-side
- Enable RLS before production deployment
- Rotate credentials regularly

## 🐛 Troubleshooting

See [SUPABASE_SETUP.md](docs/SUPABASE_SETUP.md#troubleshooting) for common issues and solutions.

---

© 2025 Kimpay Technologies. All Rights Reserved.

