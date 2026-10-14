# Upgrading

## Unreleased: starter defaults, setup card, table help

1. **Add the defaults changelog** after the changesets that create the `email_*` tables (`docs/ddl/email-postgres.sql`) in the host's master changelog:

   ```yaml
   - include: { file: db/liquibase/qbit-email/email-defaults.changelog.sql }
   ```

   It is insert-only and guarded: a host that already has any brand gets nothing, a host without the table skips it. The `neutral` brand it adds is not the default brand, so existing mail renders exactly as before.
2. **Behavior change.** Messages sent from a template whose Owner Qbit is set, with no sender named in the request, now record that qbit as `senderQbit` (it was empty). Nothing else about sending changes.
3. The help bundle and the Setup card need no configuration. Existing built-in templates created before this release keep an empty Owner Qbit.
