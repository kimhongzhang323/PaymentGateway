# Security Policy

## Supported Versions

The following table outlines which versions of the Kimpay Payment Gateway are currently supported with security updates and patches.

| Version | Supported          |
| -------- | ------------------ |
| 5.1.x    | ✅ Supported (Active LTS) |
| 5.0.x    | ❌ Deprecated (Upgrade Required) |
| 4.0.x    | ✅ Supported (Maintenance) |
| < 4.0    | ❌ Unsupported |

---

## Reporting a Vulnerability

We take the security of our payment gateway and users’ financial data **extremely seriously**.  
If you discover a vulnerability or potential security risk in this project, please follow the responsible disclosure process below.

### 🔒 How to Report

- **Email:** [kim.hong.zhang323@gmail.com](mailto:kim.hong.zhang323@gmail.com)
- **Subject Line:** `Security Vulnerability Report - [Short Title]`
- Include:
  - A detailed description of the issue  
  - Steps to reproduce  
  - The affected component or endpoint  
  - (Optional) Proof of concept or exploit details  
  - Your contact info for follow-up

Do **not** publicly disclose the vulnerability until it has been verified and patched.

---

## 🔐 What Happens Next

1. **Acknowledgment** — You’ll receive confirmation within **48 hours** that your report has been received.  
2. **Verification** — The issue will be analyzed and reproduced by the internal security team.  
3. **Patch Process** — Once verified, we’ll prioritize a fix depending on the severity:
   - **Critical / High:** Patch within 7 days.
   - **Medium:** Patch within 14–21 days.
   - **Low:** Patch in the next scheduled release.
4. **Disclosure** — After a fix is deployed, a public advisory will be released, and the reporter may be credited (if desired).

---

## 🧩 Scope

This policy covers vulnerabilities found in:
- Core services (`payment-core`, `payment-domain`, `payment-api`)
- Encryption and decryption logic
- Payment processing pipelines
- Authentication, authorization, and data storage layers

It does **not** apply to:
- 3rd-party dependencies (e.g., Spring Boot, database drivers)
- External integrations (e.g., bank SDKs, APIs) unless directly exploited through our codebase

---

## 🧱 Security Best Practices for Contributors

When contributing to the Kimpay Payment Gateway:
- Do **not** commit credentials, API keys, or encryption keys to source control.  
- Always sanitize and validate external input.  
- Use the provided `EncryptionService` and `PaymentLogger` utilities — do not implement custom crypto unless approved.  
- Log sensitive data with masking (never store PANs or CVVs in plain text).  
- Follow OWASP Top 10 recommendations.

---

## 🕵️ Responsible Disclosure Commitment

We greatly appreciate security researchers who act responsibly and help keep the ecosystem safe.  
We **do not pursue legal action** against individuals who:
- Report vulnerabilities in good faith  
- Avoid exploiting data or user information  
- Respect our disclosure timelines

---

## 🧾 Contact

For all security-related communication:  
📧 **[kim.hong.zhang323@gmail.com](mailto:kim.hong.zhang323@gmail.com)**

---

*© 2025 Kimpay Technologies. All rights reserved.*  
*Maintained under the Kimpay Security Response Program (KSRP).*
