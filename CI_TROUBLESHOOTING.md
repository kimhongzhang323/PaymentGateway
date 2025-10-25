# CI/CD Troubleshooting Guide

## Overview
This document explains the CI/CD setup and how to troubleshoot common issues.

## Workflows

### 1. maven.yml (Primary CI)
- **Purpose**: Main CI workflow with dependency graph submission
- **Triggers**: Push and PR to main branch
- **Key Changes**:
  - Updated permissions to `contents: write` for dependency submission
  - Changed build command from `package` to `clean install` for multi-module support
  - Updated to use `maven-dependency-submission-action@v4`
  - Added `continue-on-error: true` to prevent workflow failure if dependency submission fails
  - Added conditional to only run dependency submission on push to main (not PRs)

### 2. maven-ci-alt.yml (Alternative CI)
- **Purpose**: Fallback CI workflow without dependency submission
- **Features**:
  - No dependency submission (avoids the forEach error)
  - Test reporting
  - Artifact uploads
  - Better caching strategy

## Common Issues

### Issue 1: "Cannot read properties of undefined (reading 'forEach')"

**Cause**: The `maven-dependency-submission-action` has trouble parsing multi-module Maven projects.

**Solution**:
1. The primary workflow (maven.yml) has been updated with:
   - Latest version of the action (v4)
   - `continue-on-error: true` to prevent CI failure
   - Conditional execution (only on main branch pushes)

2. If the issue persists, you can:
   - Disable the "Update dependency graph" step in maven.yml
   - Use the alternative workflow (maven-ci-alt.yml) by renaming it

### Issue 2: Module Build Order

**Cause**: Maven modules must be built in the correct order.

**Solution**: The parent pom.xml defines the correct build order:
1. payment-common
2. payment-domain
3. payment-core
4. payment-api

Always use `mvn clean install` to ensure modules are available to each other.

### Issue 3: SNAPSHOT Dependencies Not Found

**Cause**: In multi-module projects, modules depend on each other as SNAPSHOT versions.

**Solution**:
- Use `mvn clean install` instead of `mvn package`
- The install phase puts artifacts in local Maven repo where other modules can find them
- The updated workflow now uses `clean install`

## Build Commands

### Local Development
```bash
# Full build with tests
mvn clean install

# Fast build (skip tests)
mvn clean install -DskipTests

# Build specific module
mvn clean install -pl payment-common -am

# Build from specific module
mvn clean install -rf :payment-domain
```

### CI Environment
The CI automatically runs: `mvn -B clean install --file pom.xml`

## Dependency Graph Submission

The dependency graph submission helps GitHub:
- Detect vulnerable dependencies
- Provide Dependabot alerts
- Show dependency insights

If it continues to fail:
1. The workflow will still succeed (due to continue-on-error)
2. You can manually review dependencies using `mvn dependency:tree`
3. Consider using Dependabot without the submission action

## Module Structure

```
payment (parent)
├── payment-common (utilities, enums)
├── payment-domain (entities, models) → depends on payment-common
├── payment-core (business logic) → depends on payment-domain, payment-common
└── payment-api (REST API) → depends on all modules
```

## Testing the Fix

1. Push your changes to a feature branch
2. Create a PR to main
3. Check the Actions tab to see if the workflow succeeds
4. If dependency submission still fails, it won't block the build

## Additional Notes

- All library modules (common, domain, core) have `spring-boot-maven-plugin` with `<skip>true</skip>`
- Only payment-api is a runnable Spring Boot application
- Maven wrapper (./mvnw) is configured for consistent builds across environments

