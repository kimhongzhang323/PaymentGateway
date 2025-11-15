# Supabase Setup Guide for KimPay Payment Gateway

## Overview
This guide walks you through setting up Supabase as the database backend for the KimPay payment system.

## Prerequisites
- A Supabase account (sign up at https://supabase.com)
- Java 17+
- Maven

## Step 1: Create a Supabase Project

1. Go to https://supabase.com/dashboard
2. Click "New Project"
3. Fill in the project details:
   - **Name**: kimpay-payment (or your preferred name)
   - **Database Password**: Choose a strong password (save this!)
   - **Region**: Select closest to your users
4. Click "Create new project" and wait for setup to complete (~2 minutes)

## Step 2: Get Your Supabase Credentials

### Database Connection Details
1. In your Supabase dashboard, go to **Settings** > **Database**
2. Note down:
   - **Host**: `db.<your-project-ref>.supabase.co`
   - **Port**: `5432`
   - **Database name**: `postgres`
   - **User**: `postgres`
   - **Password**: The password you set during project creation

### API Keys
1. Go to **Settings** > **API**
2. Note down:
   - **Project URL**: `https://<your-project-ref>.supabase.co`
   - **anon/public key**: Your public API key
   - **service_role key**: Your secret admin key (⚠️ keep this secure!)

### JWT Secret
1. Go to **Settings** > **API** > **JWT Settings**
2. Copy the **JWT Secret**

## Step 3: Configure Your Application

### Create Environment File
1. Copy the example environment file:
   ```bash
   cp .env.example .env
   ```

2. Edit `.env` and fill in your Supabase credentials:
   ```env
   # Database
   SUPABASE_DB_HOST=db.xxxxxxxxxxxxx.supabase.co
   SUPABASE_DB_PORT=5432
   SUPABASE_DB_NAME=postgres
   SUPABASE_DB_USER=postgres
   SUPABASE_DB_PASSWORD=your-password-here
   
   # API
   SUPABASE_URL=https://xxxxxxxxxxxxx.supabase.co
   SUPABASE_ANON_KEY=your-anon-key-here
   SUPABASE_SERVICE_ROLE_KEY=your-service-role-key-here
   SUPABASE_JWT_SECRET=your-jwt-secret-here
   
   # Encryption
   PAYMENT_ENCRYPTION_KEY_BASE64=$(openssl rand -base64 32)
   ```

### For Windows PowerShell:
Generate encryption key:
```powershell
$bytes = New-Object byte[] 32
[Security.Cryptography.RNGCryptoServiceProvider]::Create().GetBytes($bytes)
[Convert]::ToBase64String($bytes)
```

## Step 4: Initialize Database Schema

### Option A: Using Supabase SQL Editor (Recommended)
1. In Supabase dashboard, go to **SQL Editor**
2. Click "New Query"
3. Copy the contents of `payment-api/src/main/resources/db/migration/V1__initial_schema.sql`
4. Paste and click "Run"
5. Verify tables were created in **Table Editor**

### Option B: Using Flyway (Automated)
The application will automatically run migrations on startup if `FLYWAY_ENABLED=true` in your `.env` file.

## Step 5: Verify Connection

### Test the Connection
Run the application:
```bash
mvnw clean install
mvnw -pl payment-api spring-boot:run
```

Look for these log messages:
```
✓ Supabase configured: https://xxxxx...xxxxx
✓ Supabase database connection successful
```

### Manual Test (Optional)
Create a test controller to verify:

```java
@RestController
@RequestMapping("/api/test")
public class SupabaseTestController {
    
    @Autowired
    private SupabaseService supabaseService;
    
    @GetMapping("/connection")
    public ResponseEntity<Map<String, Object>> testConnection() {
        Map<String, Object> response = new HashMap<>();
        response.put("connected", supabaseService.testConnection());
        response.put("version", supabaseService.getDatabaseVersion());
        return ResponseEntity.ok(response);
    }
}
```

## Step 6: Enable Row Level Security (RLS)

For production, enable RLS on sensitive tables:

1. In Supabase SQL Editor, run:
```sql
-- Enable RLS on all user-facing tables
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE merchants ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;
ALTER TABLE wallets ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment_methods ENABLE ROW LEVEL SECURITY;

-- Example policy: Users can only view their own data
CREATE POLICY "Users can view own data" ON users
    FOR SELECT
    USING (auth.uid()::bigint = id);

-- Merchants can view their own merchant data
CREATE POLICY "Merchants view own data" ON merchants
    FOR SELECT
    USING (user_id IN (
        SELECT id FROM users WHERE auth.uid()::bigint = id
    ));
```

2. Create policies for each table based on your security requirements

## Step 7: Set Up Realtime (Optional)

Enable realtime updates for transaction monitoring:

1. In Supabase dashboard, go to **Database** > **Replication**
2. Enable replication for tables you want to monitor:
   - `transactions`
   - `transaction_logs`
   - `fraud_alerts`

## Security Best Practices

### ⚠️ Important Security Notes

1. **Never commit `.env` files** - Already added to `.gitignore`
2. **Use service_role key only server-side** - Never expose in client code
3. **Enable RLS before production** - Protect user data
4. **Rotate credentials regularly** - Update keys periodically
5. **Use environment variables** - Never hardcode credentials
6. **Monitor database access** - Check Supabase logs regularly

### Recommended: Use Secrets Manager

For production, use a secrets manager:
- **AWS Secrets Manager**
- **Azure Key Vault**
- **HashiCorp Vault**
- **Google Secret Manager**

## Monitoring & Maintenance

### Check Database Health
```sql
-- Check table sizes
SELECT 
    schemaname,
    tablename,
    pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size
FROM pg_tables
WHERE schemaname = 'public'
ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC;

-- Check active connections
SELECT count(*) FROM pg_stat_activity;
```

### Backup Strategy
1. Supabase provides automatic daily backups
2. For critical data, enable Point-in-Time Recovery (PITR)
3. Test restore procedures regularly

## Troubleshooting

### Connection Failed
- Verify database password is correct
- Check if IP is whitelisted (Supabase > Settings > Database > Connection Pooling)
- Ensure connection pooling is enabled for production

### Migration Errors
- Check SQL syntax in migration files
- Verify table dependencies are created in correct order
- Check Flyway migration history: `SELECT * FROM flyway_schema_history;`

### Performance Issues
- Add indexes on frequently queried columns
- Enable connection pooling
- Use prepared statements
- Monitor slow queries in Supabase dashboard

## Next Steps

1. ✅ Database schema created
2. ✅ Connection configured
3. 🔄 Implement authentication with Supabase Auth
4. 🔄 Set up Row Level Security policies
5. 🔄 Configure API endpoints
6. 🔄 Add monitoring and alerting

## Resources

- [Supabase Documentation](https://supabase.com/docs)
- [Supabase Java Client](https://github.com/supabase-community/supabase-java)
- [PostgreSQL Documentation](https://www.postgresql.org/docs/)
- [Spring Data JPA Guide](https://spring.io/guides/gs/accessing-data-jpa/)

## Support

For issues or questions:
- Check [Supabase Community](https://github.com/supabase/supabase/discussions)
- Review application logs in `payment-api/logs/`
- Contact KimPay development team

---
Last Updated: November 15, 2025

