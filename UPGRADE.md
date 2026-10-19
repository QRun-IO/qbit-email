# Upgrading

## Unreleased: shared CRM templates

Existing hosts and callers need no configuration change. This release adds APIs and a template kind; it does not migrate CRM rows or enable shared CRM templates automatically. The existing VARCHAR kind column needs no email schema change. Hosts with additional kind constraints must allow `CRM_LEGACY` before importing migrated rows.

- To grant CRM users template access, configure `EmailQBitConfig.withTemplatePermissionBaseName("crm.manage")`. The resulting `.read` and `.write` permissions apply to template/version data and the New Version/Activate Version actions, plus app navigation. They do not grant provider, brand, delivery, message or PHI approval access. A template's `editPermissionName` is still an additional write requirement, and record security locks still apply.
- `EmailApi.templateVariableNames` and `renderTemplate` read templates and active versions in the caller's session. Rendering requires an expected delivery category and returns typed content and provenance. Native HTML must not be interpreted as Markdown. Native templates reject category/content overrides, and PHI-approved templates require the existing protected send path. Preview content is not a substitute for the actual queued message subject or delivery envelope.
- `EmailApi.sendTemplateOnce` uses the existing outbox and suppression rules, returning the exact stored subject only for a checked template/version and matching sender, linked record, category and single-recipient envelope. Supply sender qbit/process and linked table/id; CC/BCC and multiple parsed mailboxes are unsupported. Existing metadata-only lookup APIs remain unchanged. A replay after active-version or envelope changes can be rejected: reconcile the existing effect rather than automatically retrying with a new key.
- `CRM_LEGACY` is for migrated templates owned by `crm`, without PHI approval or record bindings. It preserves the five historical CRM substitutions in a single pass, leaves unknown syntax literal, prefers text source over HTML fallback, and supports historical subject/body overrides. It permits the caller's transactional or marketing category while retaining delivery suppression and readiness checks. Native kinds keep their existing policies.
- Release the public email artifact before pinning the adopting CRM release. CRM/host adoption must explicitly opt in, migrate populated rows and references, and preserve provenance and rollback data during a documented maintenance window. Optional tenant CRM hosts must retain their legacy path until a supported tenant migration exists. Local verification artifacts such as `0.1.0-LOCAL-270` must never become public dependency pins.

## Unreleased: starter defaults, setup card, table help

1. **Add the defaults changelog** after the changesets that create the `email_*` tables (`docs/ddl/email-postgres.sql`) in the host's master changelog:

   ```yaml
   - include: { file: db/liquibase/qbit-email/email-defaults.changelog.sql }
   ```

   It is insert-only and guarded: a host that already has any brand gets nothing, a host without the table skips it. The `neutral` brand it adds is not the default brand, so existing mail renders exactly as before.
2. **Behavior change.** Messages sent from a template whose Owner Qbit is set, with no sender named in the request, now record that qbit as `senderQbit` (it was empty). Nothing else about sending changes.
3. The help bundle and the Setup card need no configuration. Existing built-in templates created before this release keep an empty Owner Qbit.
