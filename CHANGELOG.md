# Changelog

All notable changes to QBit Email are documented in this file.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Phase (a), part 1 email tables, PostgreSQL DDL, enum sources, child relations, permissions and Email app navigation.
- Provider validation with environment-name allowlists and presence-only credential displays.
- Transactional ad-hoc EmailService, idempotency, suppression and a replaceable Markdown/HTML/text renderer.
- Durable dispatcher with unique attempt claims, leases, retry/dead-letter handling, shared provider rate limits, health probes and optional alerts.
- SMTP with authentication/TLS/timeouts, SES v2 raw MIME, and a Gmail SMTP preset.
- QBIT_EMAIL core messaging bridge, Review/Result actions, backend send process, dispatch/maintenance/retention schedules and host integration declarations.
- H2 concurrency and transaction tests, GreenMail end-to-end tests, fake SES tests, metadata/permission checks and process-runner coverage.
- Repository scaffold to the QRun qbit repository standard (Apache-2.0, CircleCI qqq-orb 0.6.8 with the `configure-package-install` pre-step, GA-only `publish_release`, the `release-rc` caller workflow).
