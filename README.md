# QBit Email

Phase (a): a durable email outbox for QQQ with SMTP/SES delivery, versioned
Markdown/HTML/MJML templates, branding, classified variables, PHI protection,
preview widgets, and a real-data dashboard. Every send goes through `email_message`.

The [design](docs/DESIGN.md) includes later phases. Attachments, tracking webhook
handlers, unsubscribe handlers, Preferences, and additional HTTP transports remain
later work. Preview uses the standard widget endpoint; the dedicated preview API
arrives with RC.6.

## Install

Requires Java 21, QQQ **4.1.0-RC.5**, and a transactional RDBMS with the supplied unique
constraints. Apply [the PostgreSQL DDL](docs/ddl/email-postgres.sql) before producing the qbit. SQL timestamps store UTC values without a timezone, matching QQQ RC.5.
The physical table names equal their metadata names; `backendName` selects the host's
QQQ database backend, as it does in qbit-webhooks.

```xml
<dependency>
   <groupId>com.kingsrook.qbits</groupId>
   <artifactId>qbit-email</artifactId>
   <version>${qbit-email.version}</version>
</dependency>
```

```java
new EmailQBitProducer()
   .withQBitConfig(new EmailQBitConfig()
      .withBackendName("rdbms")
      .withSchedulerName("scheduler"))
   .produce(qInstance);
```

The scheduler must already exist. Without a scheduler, callers can enqueue and run
`EmailDispatcher` or Send Now manually; automatic dispatch is not installed.
Configuration is looked up from the current `QInstance` on every call, never cached
in a static variable.

## Tables and permissions

| Table | Navigation | Writes |
|---|---|---|
| `email_message` | Messages | Qbit only; people use record actions |
| `email_message_attempt` | Attempts on a message | Qbit only |
| `email_template` | Templates | `email.manage`; template edit grants also apply |
| `email_template_version` | Versions on a template | Draft edits only; lifecycle uses record actions |
| `email_brand` | Brands | `email.manage`; template edit grants also apply |
| `email_suppression` | Suppressions | `email.manage`; changing/removing a complaint needs `email.admin` |
| `email_provider` | Providers, with child Messages | `email.admin`, including reads and test sends |

`email.view` reads messages, templates, versions, brands and suppressions.
`email.send` permits the Java service, bridge and backend send process. Reading
messages requires `email.view`, `email.manage` or `email.admin`; send alone grants no read access.
`email.manage` also allows sending, Resend, Cancel and Send Now. `email.admin`
implies all permissions. Grant `email.hasAccess` for the app's derived base access.
Enable the host's `QInstance.enforcePermissionsInCoreActions` for direct Java action
permission enforcement; the tables and processes use the shared custom checker.
Trusted system callers use `SystemWrites.call(...)`, which opens core's authorized
scope and still preserves record security locks.

All tables and columns follow the `email_`/snake_case database convention. Java field
names are camelCase, matching the webhooks reference. System tables disable direct
insert/update/delete capabilities. Labels contain no status decorations.

## Providers and credentials

Create the first enabled provider with `isDefault=true`. When any providers are
enabled, exactly one must be the enabled default. Switch defaults in a batch update,
clearing the old default first. A nullable unique `default_slot` backs the validation.
Providers with message history cannot be deleted; disable them instead.

For an unauthenticated relay, use kind `SMTP`, host/port, security `NONE`, and a
`fromAddress`. Leave credential names empty. Authenticated SMTP uses `usernameEnvName`
and `secretEnvName`; security is `NONE`, `STARTTLS` (required) or `TLS`. Timeouts are
10 seconds to connect and 15 seconds each to read and write. The Gmail SMTP preset
uses `smtp.gmail.com:587` with required STARTTLS.

SES needs a `region`, optionally `configurationSet`. With no credential references
it uses the AWS default chain. Otherwise both username and secret references are
required and resolve to the access key and secret key. SES sends raw MIME with explicit
envelope destinations, keeping BCC recipients out of the raw message headers. SDK
retries are disabled so outbox attempts own retry accounting; an API call is bounded
at 40 seconds.

Credential fields contain **environment variable names**, matching
`^[A-Z_][A-Z0-9_]{0,99}$`. The effective allowlist is `allowedEnvNames` plus these defaults
for the selected kind:

| Kind | Default allowed names |
|---|---|
| SMTP / GMAIL_SMTP | `EMAIL_SMTP_USERNAME`, `EMAIL_SMTP_PASSWORD`, `EMAIL_GMAIL_USERNAME`, `EMAIL_GMAIL_APP_PASSWORD` |
| SES | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` |
| POSTMARK | `EMAIL_POSTMARK_SERVER_TOKEN`, `EMAIL_POSTMARK_WEBHOOK_PASSWORD` |
| SENDGRID | `EMAIL_SENDGRID_API_KEY`, `EMAIL_SENDGRID_WEBHOOK_PUBLIC_KEY` |
| MAILGUN | `EMAIL_MAILGUN_API_KEY`, `EMAIL_MAILGUN_SIGNING_KEY` |
| GMAIL_API | `EMAIL_GMAIL_CLIENT_ID`, `EMAIL_GMAIL_CLIENT_SECRET`, `EMAIL_GMAIL_REFRESH_TOKEN` |

Allowlisting does not automatically select a name. Empty SMTP references mean no auth;
empty SES references mean the default chain. Explicit SES references use a static
access/secret pair; temporary session credentials belong in the default AWS chain.
`withSecretResolver(Function<String,String>)` defaults to `System::getenv`. Values are
resolved during an attempt and never retained, audited, returned or logged. The
provider record's display-only fields return only `Set` / `Not set`; checking presence
necessarily invokes the resolver too. Enqueue and internal queries do not resolve it.

POSTMARK, SENDGRID, MAILGUN and GMAIL_API have metadata but no transport in this part.
Their delivery attempts fail permanently with `not available until phase (b)`.

## Sending

```java
EmailSendResult result = EmailService.send(new EmailRequest()
   .withTo("Pat <pat@example.com>")
   .withCc("copy@example.com")
   .withSubject("Your export is ready")
   .withMarkdownBody("[Open your export](https://app.example.com/exports/42)")
   .withCategory(EmailCategory.NOTIFICATION)
   .withIdempotencyKey("export:42")
   .withLinkedRecord("export", 42)
   .withSender("exports", "complete"));
```

Use `withHtmlBody` or `withTextBody` instead of Markdown, with optional explicit text
alongside HTML. `withFrom`, `withReplyTo`, `withBcc`, `withProviderName`, `withSendAfter`,
and `withLocale` are supported. `sendingDomain`, when present, must match the sender's
domain. Addresses are parsed strictly; header newlines are refused. Markdown raw HTML
and unsafe URL schemes are disabled. Templates always use the validated template renderer. The ad-hoc renderer extension
remains compatible. Both alternatives are stored at enqueue time and reused on retries.

Results expose `messageId()`, `status()` (`QUEUED`, `DUPLICATE`, `SUPPRESSED`, `REFUSED`)
and `reasons()`. Idempotency is global within the outbox. A repeated key returns the
original id; unique constraints protect races. Idempotent inserts briefly lock the
oldest provider row so concurrent service calls do not abort a caller-owned SQL
transaction with a duplicate-key error.

`withTransaction(transaction, backendName)` joins a matching backend transaction.
The one-argument overload assumes the email backend. A different backend registers
an after-commit insert, discarded on rollback. Its immediate result has a null id
and a reason explaining that enqueue is deferred: an auto-generated id does not
exist before that callback runs. Cross-backend callbacks have the same crash window
as qbit-webhooks' fallback; use the same backend when atomic enqueue is required.
Without a caller transaction, the service owns and commits its transaction.

For multi-tenant hosts, configure `securityFields` and `recordSecurityLocks`, add the
matching columns to the DDL, and provide the request's `securityValues`. Security
values propagate to attempts and resends.

Suppression compares canonical lowercase mailboxes. ALL applies across categories,
except UNSUBSCRIBE cannot block TRANSACTIONAL or SECURITY. Category suppressions apply
to NOTIFICATION and MARKETING. Expired rows are ignored. Partial suppression drops
only matching recipients and records them in `suppressedAddresses`; all-suppressed
messages remain visible with SUPPRESSED status and never consume an attempt.

## Templates, variables and branding

Create an `email_template`, then use **New Version** to copy the active or latest
version into a DRAFT. Edit its source, schema and synthetic sample values. **Activate
Version** validates syntax, samples, classifications, brand contrast and optional
MJML compilation, then retires the previous active version for that locale in one
transaction. ACTIVE and RETIRED versions are immutable. Template-specific
`editPermissionName` applies to CRUD and actions, in addition to the email permission;
even email administrators need that explicit grant. Trusted system writes may bypass it.

```java
EmailService.send(new EmailRequest()
   .withTemplateName("order_ready")
   .withTo("pat@example.com")
   .withRecordVariable("order", "order", 123)
   .withVariable("count", 3)
   .withLocale("en"));
```

A schema is a JSON list, for example:

```json
[
  {"name":"order","label":"Order","type":"RECORD","required":true},
  {"name":"count","label":"Items","type":"NUMBER","required":true},
  {"name":"link","type":"URL","classification":"PUBLIC"}
]
```

Types are STRING, NUMBER, DATE, DATE_TIME, BOOLEAN, URL, LIST and RECORD. Optional
`table`/`field` bind a variable's classification to metadata. URL values must be
absolute HTTP(S) URLs. Use `{{name}}`, `{{order.number}}`, `{{name|fallback text}}`,
`{{#if count}}...{{/if}}`, and `{{#each items}}{{@index}}: {{this.name}}{{/each}}`.
`this` also supports scalar list elements. Empty/null/false/zero values are false.
Unknown variables, unsupported helpers, unclosed expressions and mismatched blocks
produce line-numbered errors. Records and lists allow dynamic child paths; declare
specific dotted entries when child types/requiredness matter. There is no raw output.
HTML values are escaped; Markdown values cannot inject Markdown syntax. Subject and
text contexts use plain values, with subject newlines refused.

Built-ins are `brand.*` (snake_case names such as `label`, `site_url`, `primary_color`),
`message.id` (the actual outbox primary key), `now` (UTC), and `unsubscribe_url`.
The latter is empty for TRANSACTIONAL/SECURITY and when no notification opt-out key
is configured. The requested locale resolves to an ACTIVE version, falling back to
`en`. The template supplies the category unless the request overrides it.

Brand precedence is request name, template brand, the configured app's brand,
default brand, then the built-in neutral brand. Markdown uses CommonMark tables and
autolinks; raw HTML is escaped. `[Label](https://example.com){.button}` creates a table
button. The 600px shell includes preheader, logo variants, footer, social links and
light/dark colors; marketing requires a postal address. Brand saves and activation
require 4.5:1 text and button contrast in both modes. Optional `darkPrimaryColor` and
`darkButtonTextColor` fall back to their light counterparts. Colors use `#RRGGBB`.

HTML versions retain authored HTML, with escaped variables and an opt-out footer
before `</body>`. For MJML configure `withMjmlCompiler(new CliMjmlCompiler(binaryPath))`
or another compiler implementation. Compilation uses stdin/stdout only, with a
20-second timeout; the stored `compiledHtml` is reused for sends. No runtime files,
source files or compiler temp files are written by this qbit.

The record Preview widget uses synthetic `sampleVariables`, sanitized inline light
(390px maximum) and dark (600px maximum) samples plus wrapping plain text. Next's
sanitizer removes iframes and style blocks, so this preview is not an exact mail-client
emulator. Draft MJML is compiled in memory for preview when a compiler is configured;
preview never stores the result or changes the version.

## PHI and PII

`withRecordVariable` reads through `QueryAction` in the sender's QContext; it does not
open an authorized scope. Core password masking is preserved. RC.5 does not enforce
ordinary `FieldSecurityLock` during QueryAction, so the email record loader also
applies the field lock against the sender's security keys before copying any values.
Classification precedence is reflective core `AiDataClassifications.ofField`, config
`classificationOverrides`, schema declaration, then PUBLIC.

PII/PHI variables require **Approve For PHI** (`email.admin`) on the exact version.
Approval records actor/date and `approvedVersionId`; it freezes an approved draft.
A new sensitive version must be approved before activation. Activating a version
without its own approval clears the template's old approval. Approval of one locale
version does not approve another locale. PHI-approved templates cannot be MARKETING,
use provider open/click tracking, or use a provider without `phiAllowed`. Provider
attestation and tracking are checked again immediately before dispatch.

`email_secure_message` is created transactionally on its first send. It needs only
a required URL variable `link` and brand built-ins; use it to send a sign-in link
without putting patient data into email.

PII/PHI snapshot values become `hmac:v1:<keyId>:<base64url>`, or `changed:v1` without
a valid base64 `QQQ_AUDIT_HMAC_KEY`. Record tokens bind table, field and primary key;
context tokens bind `email`, template name, variable name and message id. Context
booleans and values with fewer than four distinct characters always use `changed:v1`.
`shred:v1` is reserved for future core wipe-rule record fields and is never produced
for context values. The classification and token helpers are explicit RC.7 stand-ins.

PHI-approved subjects and HTML/text bodies are AES-256-GCM encrypted as
`enc:v1:<base64url(iv|ciphertext|tag)>`, using SHA-256 of `QQQ_EMAIL_BODY_KEY`.
Missing keys refuse the send before an outbox insert. Only dispatch decrypts; query,
record and API displays show `[encrypted]`, while home shows `Secure message`.
Resend preserves ciphertext and policy; its token snapshot stays bound to the original
message, which the new message links to. Errors and logs omit provider diagnostics
and content. Keys resolve through `secretResolver`; no secret values are stored.

NOTIFICATION/MARKETING templates prepare footer links and headers using
`QQQ_EMAIL_UNSUBSCRIBE_KEY` and `publicBaseUrl`; marketing refuses without them.
Tokens bind the message, recipient-envelope hash and category. The phase-(c)
unsubscribe route and Preferences are still pending: signed links are prepared here,
but this qbit alone does not yet serve the unsubscribe destination. Do not deploy
opt-out mail without that receiving handler.

The home uses viewer-scoped counts and queries, UTC hour/day charts, provider health
(admins), and 20 recent messages with masked recipients. No sample data or sampled
counts are used. Bounce/complaint totals use dedicated event timestamps, populated
by the future webhook phase or host integrations.

## Delivery and actions

Each claim inserts a unique `(message_id, attempt_number)` row and commits SENDING
with a lease before network I/O. Provider-row database locks serialize rate checks
and claims across replicas; they are released before contacting the provider. Limits
count attempts in rolling minute and 24-hour windows. Waiting consumes no attempt.
Per-message SMTP 5xx and SES MessageRejected/InvalidParameterValue failures become
DEAD_LETTER, as do invalid MIME addresses or headers; transient failures
use the configured backoff. Explicit retry-after values are capped at one hour;
the configured backoff itself is not capped.

Expired attempts become LEASE_EXPIRED, then retry or dead-letter at the attempt limit.
Late completion from an expired runner cannot overwrite the newer attempt's outcome.
Delivery is **at least once**: provider acceptance followed by a runner crash can
produce a repeat. Credential, authentication and SES account failures immediately mark
the provider UNHEALTHY and stop its remaining deliveries for that dispatch pass.
These attempts are recorded as PROVIDER_FAILURE and keep messages in RETRY_WAIT with
backoff. They increment the historical attempt count but do not consume the message's
maxAttempts budget (including during lease recovery). Ten consecutive other transient failures mark a provider UNHEALTHY;
one queued message probes it every five minutes, and success restores HEALTHY.
Optional core alerts raise `email.provider.unhealthy` and `email.message.dead-letter`,
with dead-letter deduplication per provider/hour. No alert sink is required.
Alert failures cannot fail an already committed delivery.

SMTP sign-in requires STARTTLS or TLS. Plaintext authentication is refused at provider
validation and again before connecting, except for loopback (`localhost`, `127.0.0.1`,
`::1`) or an explicit `withAllowPlaintextSmtpAuth(true)` host configuration (default false).
Provider sender/reply-to fields and MIME subjects/addresses cannot contain CR or LF.

Messages offer standard Review/Result actions: Resend (DEAD_LETTER, BOUNCED,
COMPLAINED, with retained content), Cancel (QUEUED/RETRY_WAIT), and Send Now
(QUEUED/RETRY_WAIT). Resend creates a new linked message and rechecks suppression.
Send Now overrides deferral but preserves rate limits, health and active leases.
Providers offer Send Test Email with a recipient input and a reported outcome.

`email_send_templated` is a hidden backend API process. Inputs include `templateName`,
`to`, `cc`, `bcc`, `replyTo`, `subject`, `markdownBody`, `htmlBody`, `textBody`,
`providerName`, `brandName`, `category`, `locale`, `variablesJson`, `recordVariablesJson`,
`linkedTableName`, `linkedRecordId`, and `idempotencyKey`. `variablesJson` is an object;
record bindings use `{"patient":{"table":"patient","id":123}}` in
`recordVariablesJson`. Output includes `messageId`, `status`, and `reasons`.

Hidden schedules are `email_dispatch` (15 s by default), `email_maintain` (300 s, lease
recovery including disabled providers, and health probes), and `email_retention`
(daily). Retention clears final-message bodies after 30 days, preserving `bodySha256`;
message deletion is disabled by default. `finishedDate` records finalization independently
of enqueue and later retention updates.

## Core messaging bridge

The producer adds messaging provider `qbitEmail` (configurable), type `QBIT_EMAIL`.
Existing callers switch only `messagingProviderName`:

```java
new SendMessageAction().execute(new SendMessageInput()
   .withMessagingProviderName("qbitEmail")
   .withTo(new Party().withAddress("pat@example.com"))
   .withSubject("Ready")
   .withContent(new Content().withContentRole(EmailContentRole.TEXT).withBody("Your export is ready.")));
```

The bridge recursively maps Party/MultiParty TO, CC, BCC, FROM and REPLY_TO roles, plus
TEXT/HTML alternatives. REFUSED throws `QException`; successful calls acknowledge
queueing, not delivery. The built-in EMAIL and SES types are unchanged.

For schema-aware core bridge calls, use the typed extension:

```java
new SendMessageAction().execute(new EmailTemplatedMessageInput()
   .withEmailRequest(new EmailRequest().withTemplateName("email_secure_message")
      .withTo("pat@example.com").withVariable("link", "https://app.example.com/inbox"))
   .withMessagingProviderName("qbitEmail"));
```

Ordinary core `SendMessageInput` remains ad-hoc and cannot infer classifications
from arbitrary pre-rendered text. Use the typed extension for classified variables.

## Configuration

All settings have fluent `withX` setters and getters.

| Setting | Default / purpose |
|---|---|
| `backendName` | Required existing backend |
| `schedulerName` | Optional existing scheduler; required for automatic dispatch |
| `publicBaseUrl` | Public app URL for signed unsubscribe links |
| `mjmlCompiler` | Optional `MjmlCompilerInterface`; required for MJML activation |
| `classificationOverrides` | Map of `table.field` to `EmailClassifications.Level` for RC.5 |
| `defaultProviderName` | Optional named provider override; otherwise enabled default row |
| `bridgeProviderName` | `qbitEmail` |
| `secretResolver`, `allowedEnvNames` | `System::getenv`, empty additional allowlist |
| `dispatchIntervalSeconds`, `batchSize` | 15, 100 |
| `maxAttempts`, `retryMinutes` | 6; 1, 5, 15, 60, 240, 720 |
| `leaseSeconds` | 120; minimum 60 |
| `bodyRetentionDays`, `messageRetentionDays` | 30, 0 (keep message rows forever) |
| `appName`, `appLabel` | `email`, `Email` |
| `securityFields`, `recordSecurityLocks` | Empty host additions |
| `tableMetaDataCustomizer` | Optional final metadata customization |

`EmailIntegrations.esbTableNames()`, `apiTableNames()` and `quickSearchTables()` expose
host integration declarations for messages, templates and suppressions. Quick Search
weights exclude subjects, bodies and variable snapshots. Hosts wire their ESB/API/search
modules to these declarations.

## Build

```bash
./mvnw -o -q verify
```

Windows can use `./mvnw.cmd -o -q verify`. No new dependencies are required. Tests use
H2, GreenMail and a fake SES client, including transaction rollback, concurrent claims,
concurrent idempotency, shared rate budgets, health recovery and process screens.
The tests also exercise template lifecycle, parent-scoped possible values, rendering,
PHI canary egress, encryption, preview widgets and home data.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
