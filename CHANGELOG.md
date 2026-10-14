# Changelog

All notable changes to QBit Email are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Changed
- Built on QQQ 4.1.0-RC.7 (default QQQ profile).

### Added
- Foreign keys between all ten tables in `docs/ddl/email-postgres.sql`, named `fk_<table>_<column>`. CASCADE: a provider's sender addresses and a message's attempts and events. RESTRICT: providers with messages, attempts, events or webhook inbox rows; a brand used by a template; a template with versions. SET NULL: a message's sender address, template, version and brand; a template's active and approved version (the template/version cycle); a suppression's source message and event, so suppressions survive message retention. Ten new indexes cover the unindexed key columns. `docs/ddl/email-foreign-keys-upgrade.sql` adds the keys to an existing database. It first records orphan rows in `qqq_fk_quarantine`: nullable references are set to NULL, and rows whose required parent is missing are deleted and kept as JSON. The script is idempotent. The provider delete check now also refuses providers with attempts, events or inbox rows. `ForeignKeyGuardrailTest` (PostgreSQL 17 via Testcontainers) fails when a join or table possible-value field between email tables has no foreign key.
- Starter defaults changelog `db/liquibase/qbit-email/email-defaults.changelog.sql` (insert-only, guarded): one non-default, high-contrast `neutral` brand, only when no brand exists. Tested through real Liquibase on H2, including that it never changes rendering.
- Help for the Email app and every `email_*` table (`help/qbit-email.yaml`, STAND-IN core#1198): what the table is for and how its rows get created, shown when it is empty.
- A Setup card first on the Email home (email admins), using the sender's own provider and brand resolution and `EmailApi.readiness`. Opening the home also creates the built-in secure-message template.
- `EmailTablesGuardrailTest`: every table is used by runtime code, seeded or listed as starting empty with a reason, and has help.

### Fixed
- `email_template.ownerQbit` was stored but never read, and the built-in template did not set it. The built-in now names `qbit-email`, the field is read-only with help, and messages sent from a template name its owner qbit as `senderQbit` unless the request names one.

### Added
- `EmailJobEffectTarget` automatically registers the durable-jobs `email` target, with idempotent outbox handoff, refusal reasons, reconciliation and job-owner session enforcement; H2 integration coverage and payload/registration documentation.
- `EmailApiReadiness.failedChecks()` and `failed(check)` with `EmailApiReadinessCheck` (NOT_INSTALLED, PROVIDER_MISSING, PROVIDER_INVALID, PHI_PROVIDER, PUBLIC_BASE_URL, UNSUBSCRIBE_KEY, BRAND_POSTAL_ADDRESS, BODY_KEY), so callers react to a specific prerequisite without parsing reason text.
- `EmailApi.sendOnce` as the public durable-effect handoff, with API-only outcome/result types, committed enqueue/replay semantics and suppression/transaction refusal parity. Exact idempotency-key and RFC Message-ID lookups return `EmailApiMessage`, which now includes `rfcMessageId` across all lookup routes. Public requests support ordinary caller transactions; `EmailEffects`, `EmailEffectOutcome` and `EmailMessageRef` are deprecated internals. H2 parity, reconciliation, storage-failure and API-boundary coverage; no new dependencies.
- Stable `com.kingsrook.qbits.email.api` contract for cross-qbit sends, create-once templates, metadata-only message lookups, enabled sender addresses, default provider selection and secret-safe readiness (including marketing and PHI prerequisites). JDK/QQQ-only signatures, complete request/status mapping tests and a reflection package-boundary guard; no new dependencies.
- Documented both supported cross-qbit routes: core-only `SendMessageAction` via `qbitEmail`, and the public API for richer sends and lookups.
- Persisted unique RFC Message-IDs, stable across delivery attempts, with SMTP/Gmail SMTP, SES raw MIME and Postmark/SendGrid/Mailgun request propagation (SES replaces the header downstream).
- Core-independent `EmailEffects` handoff, reference lookups by idempotency key or RFC Message-ID and refusal mapping; offline retry and provider-contract tests.
- Checkstyle now checks test sources as well as main sources; existing test imports and formatting corrected.
- Provider sender addresses: child table `email_provider_sender_address` (validated lowercase address, display name, one default, enabled, note; unique per provider; domain bound to the provider's sending domain; admin-only), shown on the provider record. `EmailRequest.withFromAddressId`, `from_address_id` on messages, refusal of a from address that is not an enabled row of the resolved provider, and the provider `from_address` / `from_name` kept as the fallback.
- Contingent possible-value filters on the new from-address pickers (send processes by `providerId`, Send Test Email by the acted-on provider, message `fromAddressId` by `providerId`) and on the attempt and event message pickers (by `providerId`) and the suppression source event (by `sourceMessageId`); the send processes also take `providerId`.
- Phase (c): authenticated SNS, Postmark, SendGrid and Mailgun webhooks, durable inbox deduplication and Mailgun nonce replay protection; configurable Javalin routes.
- Atomic scheduled event processing, per-recipient event history, monotonic delivery state, category-aware suppression, optional daily complaint alerts and FAILED inbox Reprocess with Review/Result.
- One-click unsubscribe and escaped self-contained light/dark confirmation pages using the existing envelope-bound signer; account/security tokens refused.
- Event-based home totals, PostgreSQL/H2 tracking DDL, provider setup instructions, test-only SNS certificate/key and real HTTP, crypto, parser, concurrency, retry and privacy tests. Preferences stays deferred until core #1162 reaches an RC.
- Phase (a), part 2: versioned Markdown/HTML/MJML templates, typed variable schemas, escaped blocks/loops/fallbacks, branded light/dark layout and contrast validation.
- New Version, Activate Version and version-bound Approve For PHI actions, edit permissions, immutable history and parent-scoped version selectors.
- Sender-scoped record variables, classification stand-in, HMAC snapshot tokens, AES-GCM bodies, encrypted query displays and protected resend.
- Lazy secure-message built-in, typed templated core bridge, process JSON variables, inline preview and viewer-scoped dashboard charts/cards.
- PHI canary egress, masking, lifecycle, parent-filter, rendering, branding and GreenMail template tests.

- Phase (b) Postmark, SendGrid and Mailgun HTTP transports with fixed HTTPS endpoints, bounded requests, tracking options, safe error classification and Retry-After support; no new dependencies or filesystem storage.
- Strict Gmail SMTP credential/STARTTLS validation, provider-specific credential and webhook environment defaults, and the [provider setup guide](docs/PROVIDERS.md).
- HTTP request-contract tests and local-server end-to-end outbox tests covering success, provider failures, permanent rejection, retries and secret-safe stored errors.
- Phase (a), part 1 email tables, PostgreSQL DDL, enum sources, child relations, permissions and Email app navigation.
- Provider validation with environment-name allowlists and presence-only credential displays.
- Transactional ad-hoc EmailService, idempotency, suppression and a replaceable Markdown/HTML/text renderer.
- Durable dispatcher with unique attempt claims, leases, retry/dead-letter handling, shared provider rate limits, health probes and optional alerts.
- SMTP with authentication/TLS/timeouts, SES v2 raw MIME, and a Gmail SMTP preset.
- QBIT_EMAIL core messaging bridge, Review/Result actions, backend send process, dispatch/maintenance/retention schedules and host integration declarations.
- H2 concurrency and transaction tests, GreenMail end-to-end tests, fake SES tests, metadata/permission checks and process-runner coverage.
- Repository scaffold to the QRun qbit repository standard (Apache-2.0, CircleCI qqq-orb 0.6.8 with the `configure-package-install` pre-step, GA-only `publish_release`, the `release-rc` caller workflow).
