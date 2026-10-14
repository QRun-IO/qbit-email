# AGENTS.md

This file provides guidance to coding agents working in this repository (same content as CLAUDE.md).

## Project Overview

**QBit Email**: one email service for a QQQ system (outbox, providers, templates with branding,
delivery tracking, suppression, unsubscribe, PHI-safe sending; inbound in phase 2).
Design: `docs/DESIGN.md`.

## Build Commands

```bash
./mvnw compile          # always use the wrapper: Maven 3.10 cannot read the parent POM
./mvnw test
./mvnw verify           # tests + Checkstyle + JaCoCo gate (70% instructions, 90% classes)
```

## Architecture

- **Entry point:** `EmailQBitProducer implements QBitMetaDataProducer<EmailQBitConfig>`, package `com.kingsrook.qbits.email`.
- Packages are added per phase as `docs/DESIGN.md` describes.

## Invariants

- Every send goes through the outbox (`email_message`); nothing sends inline.
- Provider credentials are environment-variable NAMES; values are never stored, logged or returned.
- PHI and PII never reach a body or a variable snapshot unless the template is `phiApproved`; snapshots follow the secured-data redaction and HMAC rules.

## Naming (owner rule)

- Tables are lowercase snake_case, prefixed `email_`; the backend name is the table name; columns are snake_case.
- Permissions use the full qbit name: `email.view`, `email.send`, `email.manage`, `email.admin`.
- Data lives in standard visible tables. Processes are table or record actions, never in the nav. The nav lists only the main tables.
- UI endpoints are `/qqq/v1` spec extensions (`docs/UI-API-EXTENSIONS.md` in QRun-IO/qqq); provider webhooks are under `/qqq/hooks/email/...`.

## Code Style

3-space indent, braces on the next line, `return (x);`, flower-box Javadoc on every class and method, fluent `withX()` setters.

## Release

- CircleCI qqq-orb 0.6.8.
- `develop` publishes SNAPSHOTs.
- `release/*` publishes RCs (automated by `.github/workflows/release-rc.yml`).
- `vX.Y.Z` tags publish releases (GA tags only).
- Never push `hotfix/*` or `release/*` branches by hand; use `feature/` or `fix/`.
