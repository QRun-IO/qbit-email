# Changelog

All notable changes to QBit Email are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
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
