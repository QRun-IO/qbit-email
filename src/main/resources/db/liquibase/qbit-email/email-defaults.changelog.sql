-- liquibase formatted sql

-- qbit-email starter defaults: one neutral, high-contrast brand to copy or select,
-- so the Brands table is not empty and its colors are a safe starting point.
-- Include it from the host's master changelog AFTER the changesets that create the
-- email_* tables (docs/ddl/email-postgres.sql):
--
--   - include: { file: db/liquibase/qbit-email/email-defaults.changelog.sql }
--
-- Rollback is empty on purpose: a name-based delete would also remove rows the host already had.
-- Rules for this file:
--   * insert-only, and only when the host has no brand at all, so a host that already
--     configured its branding is never touched;
--   * it never changes how mail looks on deploy: the brand is NOT the default brand
--     (is_default = FALSE), so it applies only when someone selects it (a template's brand,
--     a request's brand name) or ticks Default Brand on it;
--   * no secrets, and portable SQL (PostgreSQL and H2);
--   * every changeset has a precondition (sql-check only; table-exists is not portable across
--     Liquibase versions), so a host without the table skips it (MARK_RAN).
-- Providers, sender addresses, suppression and every runtime table are NOT seeded: a provider
-- is the host's own connection (a server and the NAMES of its secret variables). The built-in
-- secure-message template is created by the qbit itself on first use and when the Email home opens.
-- EmailDefaultsChangelogTest runs this file through Liquibase on a fresh schema.

-- changeset qbit-email:defaults-1
-- preconditions onFail:MARK_RAN onError:MARK_RAN
-- precondition-sql-check expectedResult:0 SELECT COUNT(*) FROM email_brand
-- comment: A neutral brand (white background, near-black text, blue buttons; light and dark schemes, every pair at least 4.5:1 contrast). Not the default brand, so nothing changes until it is selected.
INSERT INTO email_brand (name, label, primary_color, text_color, background_color, button_text_color, dark_primary_color, dark_text_color, dark_background_color, dark_button_text_color, is_default, create_date, modify_date)
SELECT 'neutral', 'Neutral', '#2459a6', '#171717', '#ffffff', '#ffffff', '#8ab4f8', '#f5f5f5', '#121212', '#000000', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM email_brand);
-- rollback empty
