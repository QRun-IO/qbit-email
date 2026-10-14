# QBit Email

Phase (a), part 1: a durable email outbox for QQQ, with SMTP and Amazon SES transports,
suppression, retries, provider health, record actions, and the core messaging bridge.
Every send is queued in `email_message` before a dispatcher contacts a provider.

The approved [design](docs/DESIGN.md) covers later phases too. This implementation
accepts ad-hoc Markdown, HTML and text. Template rendering, branding, PHI handling and
the full home dashboard belong to part 2. Template and variable-bearing requests are
refused in part 1; attachments are refused. Tracking webhooks, unsubscribe and the
additional HTTP transports remain later phases.

## Install

Requires Java 21, QQQ **4.1.0-RC.5**, and a transactional RDBMS with the supplied unique
constraints. Apply [the PostgreSQL DDL](docs/ddl/email-postgres.sql) before producing
the qbit. SQL timestamps store UTC values without a timezone, matching QQQ RC.5.
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
| `email_template` | Templates | `email.manage`; rendering arrives in part 2 |
| `email_template_version` | Versions on a template | `email.manage`; activation arrives in part 2 |
| `email_brand` | Brands | `email.manage`; rendering arrives in part 2 |
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
and unsafe URL schemes are disabled. `EmailRenderer` is the replacement seam for
part 2. Both alternatives are stored at enqueue time and reused on retries.

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
`providerName`, `brandName`, `category`, `variablesJson`, `linkedTableName`,
`linkedRecordId`, and `idempotencyKey`. Ad-hoc content works now; template/brand/variable
inputs return REFUSED until part 2. Output includes `messageId`, `status`, and `reasons`.

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

## Configuration

All settings have fluent `withX` setters and getters.

| Setting | Default / purpose |
|---|---|
| `backendName` | Required existing backend |
| `schedulerName` | Optional existing scheduler; required for automatic dispatch |
| `publicBaseUrl` | Reserved for later unsubscribe links |
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
See [implementation notes](docs/PART1-NOTES.md) for design/API accommodations.

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
