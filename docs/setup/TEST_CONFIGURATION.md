# Test Configuration Guide

## Overview
This document explains how tests are configured to run without requiring a live Supabase connection.

## Test Profile Configuration

### Test Properties (`application-test.yml`)
- Uses H2 in-memory database instead of PostgreSQL
- Disables Flyway migrations
- Disables Supabase configuration validation
- Provides test encryption key

### Key Settings

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
  
  flyway:
    enabled: false

supabase:
  enabled: false
```

## Running Tests

### Local
```bash
mvn test
```

### Specific Module
```bash
mvn -pl payment-api test
```

### With Coverage
```bash
mvn clean verify
```

## CI/CD Integration

Tests run automatically in GitHub Actions without requiring:
- Database credentials
- .env file
- External dependencies

## Troubleshooting

### Test Fails with Database Connection Error
- Ensure `@ActiveProfiles("test")` is on test class
- Check `application-test.yml` exists in `src/test/resources`
- Verify H2 dependency is in pom.xml

### Context Load Failures
- Check all required properties have test defaults
- Verify conditional beans use `@ConditionalOnProperty` appropriately

## Adding New Tests

1. Annotate test class:
```java
@SpringBootTest
@ActiveProfiles("test")
class MyServiceTest {
    // tests
}
```

2. Use in-memory H2 database automatically
3. No need for .env or Supabase credentials

