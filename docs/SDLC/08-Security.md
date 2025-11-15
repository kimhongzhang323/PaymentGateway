# 8. Security

## 8.1 Principles
- Least privilege, defense-in-depth, secure by default

## 8.2 Data Protection
- AES-256-GCM encryption for sensitive fields
- Key management via environment variable (local) / secrets manager (prod)

## 8.3 Access Control
- Spring Security; role-based authorization
- Supabase RLS (planned)

## 8.4 Compliance
- PCI DSS alignment; avoid storing PANs; mask and tokenize

