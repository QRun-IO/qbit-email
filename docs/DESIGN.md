# qbit-email design

**Status:** phases (a), (b) and (c) implemented, except Preferences (8.3), deferred until core #1162 ships in an RC. Phase (d) consumer migrations and phase (e) inbound remain specification.
**Brief:** owner decisions of 2026-10-13 (providers, outbound first, both template kinds).
**Owner decisions (2026-10-12): all recommendations accepted.** Every "Recommended" choice and
every decision below stands; it is adjusted once it can be seen working.

One email service for a whole QQQ system. Every qbit and app that sends mail queues it through
this qbit's outbox and gets the same providers, templates, branding, tracking, suppression,
unsubscribe and PHI rules.

| # | Decision |
|---|---|
| D1 | Build on core's messaging SPI (`MessagingProviderInterface`, `SendMessageAction`). The qbit registers one provider type, `QBIT_EMAIL`, that writes to the outbox, so existing `SendMessageAction` callers move by pointing their `messagingProviderName` at it (section 2.4). Core's built-in `EMAIL` and `SES` types are not overridden. |
| D2 | Delivery transports live in the qbit (section 3): core's SMTP provider has no auth, STARTTLS or timeouts, and its SES provider uses AWS SDK v1 with static keys in metadata. No core PR is required; the transports can move to core later without changing the tables. |
| D3 | Providers are data rows (`email_provider`). Credentials are environment-variable NAMES, never values (the qbit-assistant rule). |
| D4 | Every send goes through the outbox (`email_message`); nothing sends inline. Async, at least once, retries with backoff, dead letters, idempotency keys. |
| D5 | Two template kinds: Markdown with `{{variables}}` inside the branded responsive layout (the default), and raw MJML or HTML (marketing). Templates are versioned. |
| D6 | Tracking events (SES via SNS, Postmark, SendGrid, Mailgun) arrive at `/qqq/hooks/email/...` (not the UI API), are signature-verified, and land in a durable inbox before processing. |
| D7 | Hard bounces and complaints suppress the address. One-click unsubscribe (RFC 8058) records a suppression for the message's category, and users see it as a Preferences section (core #1162). |
| D8 | PHI-safe by default: PII/PHI-classified variables are refused unless the template is `phi_approved`. The recommended PHI pattern is a "secure message" (a sign-in link, no PHI in the body). |
| D9 | Gmail: SMTP with an app password in phase (b), as a preset of the SMTP transport. The Gmail API with OAuth is specified (section 3.6) and built only on request. |
| D10 | MJML is compiled when a version is activated, by a pluggable compiler (default: the `mjml` CLI when configured, through stdin and stdout). Sends never need the compiler; HTML templates need nothing. |
| D11 | Owner rule (2026-10-13): no files or filesystem, only the database and S3. Attachments, rendered bodies and (phase e) inbound mail live in the database or in object storage (qbit-object-storage), never on local disk; the services are stateless, so any replica can send, receive a webhook or render a preview. |

## 1. Flow

```
 EmailService.send(EmailRequest)     ─┐
 email_send_templated (process)      ─┤
 SendMessageAction (type QBIT_EMAIL) ─┼─► EmailOutbox.enqueue ─► email_message (QUEUED; rendered, redacted)
 record action "Send Test Email"     ─┘     idempotency key unique; suppression and PHI checks first
                                                     │
                   scheduler (every 15 s) ─► EmailDispatcher.runOnce
                      claim attempt n (unique insert into email_message_attempt)
                      ─► provider rate limit ─► EmailTransport.send (SMTP | SES | Postmark | SendGrid | Mailgun | Gmail)
                      ─► SENT (provider message id) │ RETRY_WAIT (backoff) │ DEAD_LETTER
                                                     │
 provider webhook ─► /qqq/hooks/email/{kind}/{providerName} ─► verify ─► email_webhook_inbox (durable)
                      ─► inbox processor ─► email_event (DELIVERED, BOUNCED, COMPLAINED, OPENED, CLICKED, ...)
                      ─► message status ─► email_suppression (hard bounce, complaint)
 List-Unsubscribe one-click POST ─► /qqq/hooks/email/unsubscribe/{token} ─► email_suppression (category)
```

## 2. Sending API

### 2.1 `EmailService` (Java, for every consumer)

```java
EmailSendResult result = EmailService.send(new EmailRequest()
   .withTemplateName("alerts_notification")        // or withSubject + withMarkdownBody / withHtmlBody for ad-hoc mail
   .withTo("pat@example.com")                      // withCc, withBcc, withReplyTo; "addr" or "Name <addr>"
   .withVariable("count", 3)                       // plain values, checked against the template's schema
   .withRecordVariable("order", "order", 1234)     // a record, read as the sender; classification known
   .withCategory(EmailCategory.NOTIFICATION)       // default: the template's
   .withProviderName("pmg")                        // optional; else the default provider
   .withFromAddressId(4)                           // optional enabled email_provider_sender_address row of that provider
   .withBrandName("kof22")                         // optional; else the template's, else the default brand
   .withIdempotencyKey("alerts:notif:991")         // optional; a repeat returns the existing message
   .withLinkedRecord("alerts_alert", 991)          // shown as a link on the message record
   .withSender("qbit-alerts", "alerts_notify")     // sender qbit and process
   .withSendAfter(instant));                       // optional deferral
// EmailSendResult { messageId, status (QUEUED | DUPLICATE | SUPPRESSED | REFUSED), reasons }
```

- Runs inside the caller's transaction when the backends match (the row commits with the caller's
  write), else after commit, as qbit-webhooks does.
- Validation is synchronous: unknown template, missing required variables, bad addresses, a
  PII/PHI variable on a template that is not `phi_approved` (section 7), and a disabled or missing
  provider all return `REFUSED` with plain reasons, and nothing is queued.
- All recipients suppressed for the category: `SUPPRESSED`; the message is stored with that status
  (visible in the outbox) and not sent. Partly suppressed: those addresses are dropped and listed
  on the message.

### 2.2 Process `email_send_templated`
The same request as a backend process (template, to, cc, bcc, variables JSON, linked table and
id, idempotency key, provider by `providerId` or `providerName`, `fromAddressId` filtered by the
chosen `providerId`, brand) for scripts, the API and other qbits. Not in the nav;
callable through the API and `/qqq/v1/processes`.

### 2.3 Permissions
`email.view` (read every `email_` table), `email.send` (send through the API or the process;
system callers use the authorized scope), `email.manage` (templates, brands, suppressions, resend,
cancel), `email.admin` (providers, test send, PHI approval, removing complaint suppressions). A
template may set `edit_permission_name` to restrict who edits it (e.g. marketing templates to
`marketing.manage`).

### 2.4 Core messaging bridge
- `EmailOutboxMessagingProviderMetaData` (type `QBIT_EMAIL`, a `QMessagingProviderMetaData`)
  registers `EmailOutboxMessagingProvider` with `QMessagingProviderDispatcher`. The producer adds
  one instance named `qbitEmail` (configurable).
- `sendMessage(SendMessageInput)` maps the parties (`EmailPartyRole` TO/CC/BCC/FROM), subject and
  contents (`EmailContentRole` TEXT/HTML) to an ad-hoc `EmailRequest` (no template, category
  TRANSACTIONAL unless configured) and enqueues it. It throws on `REFUSED`, so callers still see
  failures; delivery is then asynchronous.
- Attachments are stored through qbit-object-storage when it is installed, else refused with a
  plain message (no blobs in the outbox, nothing on local disk: D11).
- Callers (qbit-alerts `EmailChannel` and `NotificationEmailer`, qbit-marketing
  `QqqMessagingEmailTransport`) can switch by configuration at once, then move to `EmailService`
  in phase (d) for templates, categories, idempotency and linked records.

## 3. Providers and transports

### 3.1 `email_provider`
| Field | Notes |
|---|---|
| name, label | unique name, used by `withProviderName` |
| kind | SMTP, SES, POSTMARK, SENDGRID, MAILGUN, GMAIL_SMTP, GMAIL_API |
| enabled, is_default | exactly one enabled default (validated) |
| from_address, from_name, reply_to | defaults for messages |
| sending_domain | the domain the provider is verified for; other from domains are refused |
| host, port, security | SMTP: NONE, STARTTLS, TLS; timeouts fixed (10 s connect, 15 s read and write) |
| region | SES region; Mailgun region (US or EU) |
| username_env_name, secret_env_name | env-var NAMES only (`^[A-Z_][A-Z0-9_]{0,99}$`, and in the config allowlist) |
| webhook_secret_env_name | env-var NAME for webhook verification (Mailgun signing key, SendGrid public key, Postmark basic-auth password) |
| sns_topic_arn, configuration_set, message_stream | SES events topic and configuration set; Postmark message stream |
| max_per_minute, max_per_day | counted from `email_message_attempt` in the database, so they hold across replicas |
| track_opens, track_clicks | off by default; refused for `phi_approved` templates |
| phi_allowed | admin attestation that the provider may carry PHI (BAA in place, or an internal relay) |
| health_status, last_success_date, last_failure_date, last_failure_message | kept by the dispatcher |

Secrets resolve through `EmailQBitConfig.secretResolver` (default `System::getenv`) at send time.
They are never stored, logged, audited, put in process output or returned. The record screen shows
whether each named variable is set (yes or no), never the value.

**Record action "Send Test Email"** (`email.admin`): queues a fixed test message to a given
address through that provider and reports the outcome on the standard result screen. An optional
`fromAddressId` picker lists only that provider's sender addresses.

**Sender addresses** (`email_provider_sender_address`, a child list on the provider, `email.admin`):
provider_id, address (validated, lowercase), display_name, is_default (at most one enabled default
per provider), enabled, note; unique (provider_id, address); the domain must equal `sending_domain`
when that is set. The provider's `from_address` / `from_name` remain the fallback; an enabled default
row wins when present. A chosen id, or an explicit from text once the provider has rows, must match an
enabled row of the resolved provider or the send is `REFUSED` ("That from address is not set up for
provider <name>"). The row used is kept as `email_message.from_address_id`.

### 3.2 `EmailTransport` SPI (in the qbit)
```java
public interface EmailTransport
{
   EmailTransportResult send(EmailEnvelope envelope, EmailProviderSettings settings) throws EmailTransportException;
}
```
`EmailEnvelope` holds the rendered MIME parts: from, to, cc, bcc, reply-to, subject, HTML, text,
and headers (`List-Unsubscribe`, `Auto-Submitted`, `X-QQQ-Email-Id`). The result is the
provider's message id. The exception says whether the failure is **permanent** (rejected address,
sender refused, 4xx validation) or **transient** (connection, 5xx, throttling with an optional
retry-after).

### 3.3 SMTP (phase a)
`jakarta.mail` (already in core): auth when a username env name is set, STARTTLS or implicit TLS,
connect/read/write timeouts (the kof22 `Mailer` lesson: a stuck relay must not pin a thread).
Covers PMG (`smtp.galaxy.lan:26`, no auth), any relay, and Gmail SMTP.

### 3.4 SES (phase a)
AWS SDK v2 `sesv2` `SendEmail` with raw content, so our MIME and headers are kept. Credentials:
the default AWS chain when no env names are set (IRSA, instance role, `AWS_*`), else the named
access-key and secret env vars. The configuration set routes events to SNS (section 6).

### 3.5 Postmark, SendGrid, Mailgun (phase b)
HTTP JSON APIs through `java.net.http`, no vendor SDKs: Postmark `POST /email` (server-token
header, message stream), SendGrid `POST /v3/mail/send` (bearer key; `X-Message-Id`), Mailgun
`POST /v3/{domain}/messages.mime` (basic auth `api:key`, US or EU base). 429 and 5xx are
transient; other 4xx are permanent.

### 3.6 Gmail
- **Phase (b): `GMAIL_SMTP`**, a preset of SMTP (`smtp.gmail.com:587`, STARTTLS) with the account
  as username and an app password env var. `docs/PROVIDERS.md` documents it: it needs 2-step
  verification and an app password; Google's daily limits (about 500 for a personal account, 2000
  for Workspace) go in `max_per_day`.
- **`GMAIL_API` (specified, built on request):** OAuth client id, secret and refresh token (env
  names), `users.messages.send` with the raw MIME. It needs a Google Cloud project and consent
  screen and gives system mail nothing the app password does not, so it is deferred.

## 4. Outbox and delivery

### 4.1 `email_message`
| Field | Notes |
|---|---|
| status | QUEUED, SENDING, SENT, DELIVERED, RETRY_WAIT, DEAD_LETTER, BOUNCED, COMPLAINED, SUPPRESSED, CANCELLED |
| to_addresses, cc_addresses, bcc_addresses, from_address, reply_to | addresses are PII; the table is classified PII (section 7) |
| subject | rendered; encrypted for `phi_approved` templates (section 7.3) |
| template_id, template_version_id, brand_id, category, locale | |
| variables_snapshot | JSON, redacted per section 7.3 |
| html_body, text_body | rendered at queue time so retries send the same bytes; encrypted for `phi_approved`; cleared after `bodyRetentionDays` (default 30) once final, keeping `body_sha256` |
| idempotency_key | unique when present |
| provider_id, provider_message_id | |
| attempt_count, next_attempt_date, lease_expires_date, last_error, sent_date, delivered_date, dead_letter_date | |
| linked_table_name, linked_record_id | rendered as a link on the record screen |
| sender_qbit, sender_process | |
| suppressed_addresses | addresses dropped at queue time |

Sub-tables, shown as relations on the message: `email_message_attempt` (message_id and
attempt_number unique as a pair, outcome, provider_id, claimed_by, start and end dates, duration,
error) and `email_event` (section 6).

### 4.2 Semantics (as qbit-webhooks, proven across replicas)
- **Claim:** to send attempt *n* a runner inserts (`message_id`, *n*) into
  `email_message_attempt`; the pair is unique, so exactly one runner sends.
- **Lease:** the winner marks the message SENDING with a 120 s lease; an expired lease is retried
  as attempt *n+1*.
- **At least once:** a runner that dies after the provider accepted, but before recording it,
  causes at most one repeat. None of the providers offers a send-idempotency key, so the spec says
  so plainly.
- **Outcomes:** success → SENT. Transient → RETRY_WAIT after `retryMinutes` (1, 5, 15, 60, 240,
  720; retry-after honoured, capped at 1 h). Permanent → DEAD_LETTER at once. After `maxAttempts`
  (6) → DEAD_LETTER.
- **Rate limits** per provider per minute and per day, from the database. Over a limit the message
  waits; that is not an attempt.
- **Provider health:** HEALTHY → UNHEALTHY after 10 consecutive transient failures; while
  unhealthy one message is tried every 5 minutes; a success → HEALTHY. Alerts (optional, section
  9): `email.provider.unhealthy` (ERROR, resolved on recovery) and `email.message.dead_letter`
  (WARNING, deduplicated per provider per hour).
- **Record actions:** Resend (dead letters and bounces: a new linked message with the same
  content), Cancel (queued or waiting), Send Now (waiting). Standard streamed-ETL Review and Result
  screens.
- **Retention:** a daily job clears bodies after `bodyRetentionDays` and deletes final messages
  older than `messageRetentionDays` (0 = keep forever; kof22 keeps). NOTIFICATION and MARKETING
  messages are never deleted, because their unsubscribe links resolve through the message row
  (8.2); at `messageRetentionDays` retention only clears their bodies and variable snapshot.

## 5. Templates and branding

### 5.1 Tables
- `email_template`: name (unique, snake_case), label, category (TRANSACTIONAL, NOTIFICATION,
  MARKETING, SECURITY), kind (MARKDOWN, MJML, HTML), brand_id, active_version_id, phi_approved,
  phi_approved_by, phi_approved_date, edit_permission_name, owner_qbit, description.
- `email_template_version` (child list on the template): template_id, version_number, status
  (DRAFT, ACTIVE, RETIRED), locale (default `en`), subject, preheader, body_source, text_source
  (optional), compiled_html (MJML), variables_schema (JSON), sample_variables (JSON), created_by,
  activated_date.
- `email_brand`: name, label, logo_url, logo_dark_url, logo_alt, primary_color, text_color,
  background_color, dark_background_color, dark_text_color, button_text_color, footer_markdown,
  postal_address (shown on marketing mail, for CAN-SPAM), social_links (JSON list of label and
  url), site_url, is_default, app_name (optional: the brand for that app's sends).

An ACTIVE version cannot be edited. **Record action "New Version"** copies it to a DRAFT;
**"Activate Version"** validates it (schema, a render with the samples, the PHI check, MJML
compile, brand contrast) and makes it active, retiring the previous one. A message always records
the exact version it used.

### 5.2 Rendering
- **Variables:** `{{name}}` and dotted paths (`{{order.number}}`), always HTML-escaped;
  `{{#if name}}…{{/if}}` and `{{#each items}}…{{/each}}`; `{{name|fallback text}}`. No unescaped
  output and no helpers that run code. Unknown variables fail activation, not sends.
- **Schema:** each variable has name, label, type (STRING, NUMBER, DATE, DATE_TIME, BOOLEAN, URL,
  LIST, RECORD), required, an optional classification, and an optional bound `table` and `field`
  (the classification then comes from the field, section 7).
- **Markdown** (commonmark-java with GFM tables and autolinks; raw HTML in Markdown disabled)
  renders into the branded layout: a fixed, tested, table-based responsive shell (600 px max,
  single column, inline styles plus one `<style>` block for media queries) with the logo, a hidden
  preheader, the body, a button style for links written `[Label](url){.button}`, and the footer
  (brand footer, postal address for MARKETING, the unsubscribe link when it applies).
- **Dark mode:** the layout declares `color-scheme: light dark` and `supported-color-schemes`,
  switches to the brand's dark colours under `@media (prefers-color-scheme: dark)` (and the
  `[data-ogsc]` hooks Outlook uses), uses the dark logo when set, never puts text over a
  background image, and keeps text and button contrast at WCAG AA in both schemes. Activation
  checks the brand colours and refuses a brand that fails, quoting the ratio.
- **MJML:** compiled at activation by `MjmlCompilerInterface` (default `CliMjmlCompiler`, which
  runs the configured `mjml` binary with a timeout). With no compiler configured, MJML versions
  cannot be activated, and say so. The compiled HTML is stored on the version.
- **HTML:** used as given; variables are still substituted and escaped. The qbit adds the
  unsubscribe footer and headers for MARKETING and NOTIFICATION.
- **Plain text** is generated from the rendered HTML (links as `text (url)`, headings and lists
  kept) unless the version has its own text.
- **i18n-ready:** versions carry a locale; `EmailRequest.withLocale` picks that locale's active
  version, else the default locale's. One locale (`en`) ships.

### 5.3 Preview

**Part 2 status:** standard record widget implemented. Next's sanitizer removes
iframes and style blocks, so it uses sanitized inline light (390px maximum) and dark
(600px maximum) samples, with wrapping plain text. Draft MJML previews compile in memory
with the configured compiler; stored versions are unchanged. TODO: endpoint arrives with RC.6.

Each version record shows a **Preview** widget: the rendered email with the sample variables in a
sandboxed iframe (`srcdoc`, scripts disabled), Light/Dark and 390/600 px toggles, and the plain
text. It is served through the UI API (section 10): `POST /qqq/v1/qbit/email/preview` (a version
id, or unsaved source, plus variables) returns subject, html, text and warnings.

### 5.4 Part 2 implementation choices
- Approval records `approved_version_id` on the template. An approved DRAFT freezes;
  approve a new sensitive version before activation. Activating a different,
  unapproved version clears approval. Approval is specific to one locale version.
- `EmailBuiltIns.ensure()` seeds `email_secure_message` on its first send, using the
  database unique name and a transaction, with no static bootstrap cache.
- `dark_primary_color` and `dark_button_text_color` optionally override their light
  counterparts; both button pairs are contrast-checked. Brand colors use #RRGGBB.
- `EmailTemplatedMessageInput` extends core SendMessageInput for schema variables and
  record references. Ordinary core input remains compatible ad-hoc content.
- RC.5 QueryAction masks passwords but does not apply ordinary FieldSecurityLock;
  record-variable loading additionally applies that lock to sender session keys.
- The real outbox id is bound during the enqueue transaction, before commit. Failed
  validation persists no message. Resends preserve encrypted content and its original
  token snapshot, linking to the original message for the token binding.
- The home follows the newer Alerts pattern: standard QQQ chart widgets plus inline,
  theme-neutral cards. All queries/counts use the viewer's session. Bounce/complaint
  dates are populated by phase-(c) events; home totals count those recipient events.
- Part 2 signs opt-out links and prepares headers; phase (c) now serves the receiver. Without its key/base URL, notification omits opt-out links and
  headers, while marketing refuses as specified in 8.2. Do not use opt-out delivery
  without a receiving handler. The token's address hash binds the single recipient mailbox.

## 6. Delivery tracking

### 6.1 Inbound route
`EmailWebhookRouteProvider` (a `QJavalinRouteProviderInterface`, registered idempotently as in
qbit-easypost-tracking) serves `POST /qqq/hooks/email/{kind}/{providerName}`: size cap (413), find
the enabled provider of that kind (404), verify (401 on failure, 503 when its secret is not set),
dedup-insert the raw payload into `email_webhook_inbox` (provider_id and external_event_id unique,
status PENDING, attempts, payload_json, payload_sha256, received_date) and answer 200. A scheduled
process drains the inbox into `email_event`.

| Kind | Verification |
|---|---|
| SES (SNS) | SNS message signature (SignatureVersion 1 and 2) with the certificate from `SigningCertURL`, which must be `https://sns.<region>.amazonaws.com/...pem`; the topic ARN must be the provider's. `SubscriptionConfirmation` is confirmed by fetching `SubscribeURL` (same host rule). |
| Postmark | HTTP basic auth on the webhook URL (Postmark does not sign); password from the webhook secret env name, compared in constant time. |
| SendGrid | Signed Event Webhook: ECDSA P-256 over timestamp + body with the public key from the env name; timestamp within 5 minutes. |
| Mailgun | HMAC-SHA256 of timestamp + token with the signing key; timestamp within 5 minutes; a token seen before is refused. |

### 6.2 `email_event`
message_id (matched by provider message id or our header), provider_id, event_type (DELIVERED,
DEFERRED, BOUNCED, COMPLAINED, OPENED, CLICKED, UNSUBSCRIBED, REJECTED), bounce_type (HARD, SOFT,
UNDETERMINED), recipient, occurred_date, detail (the provider's reason, truncated; never body
content), url (clicks only). Opens and clicks are stored only when the provider row enables them.

### 6.3 Phase (c) implementation
- Inbox states: PENDING, PROCESSED, FAILED, IGNORED. Retry after configured backoff,
  FAILED after six attempts, and a standard Reprocess action resets FAILED to PENDING.
- Events in one payload are applied independently. Only unmatched events (or events whose
  message is still SENDING) defer the row; on the sixth attempt they are skipped and the row is
  PROCESSED with the note "N events did not match a message". Suppression applies only to a
  recipient in the matched message's to, cc or bcc; an SES bounce or complaint without its
  recipient list is skipped, never widened to all destinations. SNS notifications must carry a
  Timestamp no older than 1 hour and no more than 5 minutes ahead, and certificate fetch
  failures are cached for 5 minutes per URL.
- A short DB transaction locks the common provider gate then the actual provider;
  it rechecks the observed inbox attempt and commits all changes together. No worker
  filesystem, extra claim table, in-process scheduler or network operation is needed
  during processing. A crash rolls back the transaction and releases the claim.
- `email_event.event_key` provides unique recipient/event deduplication across
  overlapping batches, alongside inbox uniqueness on provider/event and provider/nonce.
- Message correlation always stays within the authenticated provider. Unknown message
  ids retry (including callbacks before SENT); unsupported event kinds are ignored.
- Opens/clicks require current provider flags and are also dropped for PHI-approved
  messages. Event details mask addresses before truncation to 500 characters.
- The unsubscribe signer binds one mailbox. A valid POST suppresses only that address
  for MARKETING/NOTIFICATION; the page never displays addresses. Legacy links remain
  valid only for messages with one To recipient and no Cc/Bcc.
- PostgreSQL and H2 definitions, crypto fixtures and real random-port Javalin tests
  are included. Certificate caching is memory-only. Setup is in `PROVIDERS.md`.

## 7. PHI and PII

### 7.1 Classification
Variables bound to a field (schema `table` + `field`, or `withRecordVariable`) take that field's
classification from core `AiDataClassifications.ofField` (`DataClassification` PUBLIC,
CONFIDENTIAL, PII, PHI; core #1142, first in 4.1.0-RC.6). Until RC.6 the qbit uses a stand-in,
`EmailClassifications` (reflective lookup of the core class, else config-declared
classifications), marked for replacement. Unbound variables may be declared PII or PHI in the
schema.

### 7.2 Refusal
- Activation and sending refuse a template whose variables include PII or PHI unless it is
  `phi_approved`. Only `email.admin` sets that, through the record action "Approve For PHI", which
  records who and when; a new version needs approval again.
- A `phi_approved` template may not use open or click tracking, may not be MARKETING, and must go
  through a provider marked `phi_allowed`.
- **The default PHI pattern** is the built-in template `email_secure_message`: "You have a new
  secure message from {{brand.label}}. Sign in to read it." with a button to the link the caller
  passes. No PHI in the body, so it needs no approval. Consumers that hold PHI use it.

### 7.3 Redaction and storage
Following core `docs/SECURED-DATA.md` (section 15, QRun-IO/qqq#1161):
- `variables_snapshot`: PII/PHI values become tokens in the format of core
  `ClassifiedValueTokens` (package `...core.actions.permissions.fieldaccess`, SECURED-DATA §14.5):
  `hmac:v1:<keyId>:<b64url(HMAC-SHA256(key, m))>`, with `m` joined by `` and the canonical
  value last, and the instance key from `QQQ_AUDIT_HMAC_KEY` (base64).
  - **Record-bound variables** use the record binding, `m = table␟field␟pk␟canonical(value)`
    (`forRecordField`), so the token matches the audit's and a field under a wipe rule gets
    `shred:v1` with the same per-record-field key: a wipe also shreds the outbox snapshot.
  - **Other values** use a domain-prefixed binding,
    `m = "email"␟template␟variable␟messageId␟canonical(value)` (`forContext`), so they never
    collide with a record binding. They get `hmac:v1` only; a low-entropy value (booleans, values
    with under 4 distinct characters) gets `changed:v1`, because there is no record key to shred.
  - No key configured: `changed:v1`.
  - Until core ships the helper, the qbit carries `EmailValueTokens`, marked
    `STAND-IN(core=#1161-impl, removeBy=RC.7)`, with the same names and output.
- The subject and bodies of `phi_approved` messages are stored AES-256-GCM encrypted with a key
  from `QQQ_EMAIL_BODY_KEY` (no key: `phi_approved` sends are refused) and cleared after
  `bodyRetentionDays` once final.
- Logs carry the message id and status only, never addresses, subjects or bodies.
- Record variables are read as the sending user, through core masking, so a value the sender may
  not see arrives masked.
- **Canary-PHI egress test:** plants `CANARY-PHI-<random>` in a PHI field, sends through every
  path (EmailService, the process, the core bridge, retry, dead letter, webhook events, an export
  of `email_message`, the home widget, captured logs) and fails on any substring of the canary
  longer than 4 characters outside the encrypted columns. It follows the core procedure so it can
  join the shared canary suite when that exists.

## 8. Suppression and unsubscribe

### 8.1 `email_suppression`
address (lowercased), scope (ALL or a category), reason (HARD_BOUNCE, COMPLAINT, UNSUBSCRIBE,
MANUAL), source_message_id, source_event_id, note, created_date, expires_date (optional). Unique on
(address, scope). `email.manage` adds and removes rows; removing a COMPLAINT needs `email.admin`.
- Hard bounce → ALL. Complaint → ALL. Unsubscribe → the message's category.
- TRANSACTIONAL and SECURITY mail ignores UNSUBSCRIBE rows (they cannot be opted out of) but
  respects HARD_BOUNCE and COMPLAINT.

### 8.2 One-click unsubscribe (RFC 8058, RFC 2369)
- NOTIFICATION or MARKETING messages carrying an unsubscribe link/header must have exactly one To recipient and no Cc/Bcc; send one message per recipient.
- MARKETING and NOTIFICATION messages carry
  `List-Unsubscribe: <https://{publicBaseUrl}/qqq/hooks/email/unsubscribe/{token}>` and
  `List-Unsubscribe-Post: List-Unsubscribe=One-Click`, and the footer links to the same URL.
- The token is `v1.<b64url(payload)>.<b64url(HMAC-SHA256)>` over the message id, an address hash
  and the category, keyed by `QQQ_EMAIL_UNSUBSCRIBE_KEY`. With no key there is no header and
  MARKETING sends are refused. Tokens do not expire (RFC 8058 expects the link to keep working).
- `POST` with `List-Unsubscribe=One-Click` records the suppression and returns 200. `GET` returns a
  minimal page with one "Unsubscribe" button that POSTs, because mail scanners prefetch GETs; then
  "You are unsubscribed from {category}". A Preferences link awaits core #1162.
- An `UNSUBSCRIBED` event is recorded on the message.

### 8.3 Preferences (core #1162)
**Deferred: core #1162 is not in an RC.** The planned qbit registers a Preferences section `email` ("Email") whose provider
(`EmailPreferenceProvider implements UserPreferenceProviderInterface`) maps the signed-in user's
address (`UserPreferenceRequestContext.getUserEmailAddress()`, never an address from the request)
to `email_suppression` rows: one toggle per opt-out-able category (NOTIFICATION,
MARKETING) and read-only rows saying that account and security mail is always sent. Qbits with
finer settings keep their own sections: qbit-alerts' "Notifications" decides which alerts are
emailed; this section decides whether NOTIFICATION mail reaches the address at all. It is built
once #1162 is in an RC; one-click unsubscribe does not depend on it. With no address on the
account, the rows are read-only ("No email address on your account") and writes are refused. The
section's description explains the split with qbit-alerts' Notifications section.

## 9. Integrations
- **Alerts (core `QAlerts`, #1108):** called reflectively when present (section 4.2).
- **ESB and Quick Search:** `email_message`, `email_template` and `email_suppression` publish table
  events and are searchable (`EmailIntegrations`); bodies and snapshots are excluded.
- **API:** the same tables, permissions and customizers as the admin (QQQ API middleware).
- **Object storage (#1089):** attachments (section 2.4) and, in phase (e), inbound attachments.

## 10. App and UI
- App **Email**, with one home dashboard (one HTML widget, real data only, inline styles, no inner
  or horizontal scroll, stacked cards under 640 px, light and dark):
  - sent, delivered, dead letters, bounced and complained in the last 24 h and 7 days;
  - sends per hour for 24 h (sent and failed);
  - suppression growth (new rows per day for 30 days, by reason);
  - provider health (status, last success, last failure reason, rate-limit headroom);
  - recent messages (newest 20: when, recipient masked as `p***@example.com`, subject unless
    `phi_approved`, status), each linking to its record.
- Nav: Messages, Templates, Brands, Suppressions, Providers. Attempts, events, versions and inbox
  rows are relations on their parents.
- UI API extension `email` (1.0.0, `/qqq/v1/qbit/email/...`): `POST /preview` (section 5.3), with
  its OpenAPI fragment committed and contract-tested. Everything else is standard tables,
  processes and widgets. The webhook and unsubscribe routes are non-UI (`/qqq/hooks/email/...`).

## 11. Consumers (phase d, one PR per repo)
| Consumer | Change |
|---|---|
| qbit-alerts | `EmailChannel` and `NotificationEmailer` use `EmailService` with templates `alerts_notification` and `alerts_digest` (NOTIFICATION), idempotency `alerts:<id>`, linked alert. `messagingProviderName` stays as the fallback when qbit-email is absent. |
| qbit-collaboration | an `EmailCollaborationNotifier` for mentions, shares and task reminders (templates `collaboration_mention`, `collaboration_share`, `collaboration_task_reminder`); record labels obey `isRecordLabelFullAccess`. |
| qbit-assistant | scheduled-task results by email through `EmailService` (template `assistant_scheduled_result`), after its EXTERNAL approval rule. |
| qbit-easypost-tracking | delivery notices through a notifier that sends `easypost_delivery_notice` (NOTIFICATION). |
| qbit-marketing | a `MarketingTransport` over `EmailService` (MARKETING, MJML/HTML templates, unsubscribe headers, suppression honoured). |
| kof22 (Website-Backend) | the contact form's `Mailer` is replaced by `EmailService` with template `kof22_contact`, through an SMTP provider row for PMG (`smtp.galaxy.lan:26`, no auth, no new secrets). A draft PR with a Liquibase changeset for the `email_` tables and seed rows. |
| Authentik | **keeps its own SMTP to PMG.** It is a separate product (Python) with its own flows and templates (recovery, enrollment, MFA); it must send when the QQQ app is down, since it guards sign-in to that app; and it has no hook to call a Java service. Its mail is account and security mail, to which suppression-by-unsubscribe would not apply anyway. |

## 12. Phase 2: inbound (design only)
- Sources: SES inbound (receipt rule → S3 → SNS → `/qqq/hooks/email/inbound/ses/{name}`), Mailgun
  routes, Postmark inbound, SendGrid inbound parse, and an IMAP or Gmail mailbox poller
  (scheduled).
- `email_inbound`: provider, message id, in_reply_to, references, from, to, subject, text and html
  (sanitized), received_date, status (NEW, ROUTED, UNMATCHED, REJECTED), routed_table, routed_id,
  spam verdicts. Attachments go to qbit-object-storage (`email_inbound_attachment` rows).
- **Reply-to-record threading:** outbound NOTIFICATION mail sets
  `Reply-To: reply+<token>@<inbound domain>`, where the token (HMAC) names the message and its
  linked record. A reply is matched by token; the sender must be the original recipient; quoted
  text is stripped; an `InboundEmailHandler` registered by the linked table's qbit acts (e.g.
  qbit-collaboration adds a comment as that user). Unmatched mail stays UNMATCHED for review.
- PHI: inbound bodies are classified PHI by default (unknown content) and stored encrypted.

## 13. Data model summary
| Table | In the nav | Written by |
|---|---|---|
| `email_message` | yes | the qbit (people: record actions only) |
| `email_message_attempt` | relation | the qbit |
| `email_event` | relation | the qbit |
| `email_template` | yes | people (`email.manage`, per-template permission) |
| `email_template_version` | relation | people (drafts), actions (activation) |
| `email_brand` | yes | people |
| `email_suppression` | yes | people and the qbit |
| `email_provider` | yes | admins |
| `email_provider_sender_address` | relation (on the provider) | admins |
| `email_webhook_inbox` | relation (on the provider) | the qbit |
| `email_inbound`, `email_inbound_attachment` | phase (e) | the qbit |

Postgres DDL in `docs/ddl/email-postgres.sql` (phases a-c).

## 14. Configuration (`EmailQBitConfig`)
backendName (required), schedulerName (required for automatic processing), publicBaseUrl (unsubscribe links),
webhookBasePath (`/qqq/hooks/email`, shared by tracking and unsubscribe),
defaultProviderName, bridgeProviderName (`qbitEmail`), secretResolver, allowedEnvNames,
dispatchIntervalSeconds (15), batchSize (100), maxAttempts (6), retryMinutes, leaseSeconds (120),
bodyRetentionDays (30), messageRetentionDays (0), mjmlCompiler, classificationOverrides, appName
and appLabel, securityFields and recordSecurityLocks (multi-tenant hosts).

## 15. Phases and acceptance
| Phase | Scope | Acceptance (tests) |
|---|---|---|
| (a) | tables, outbox and dispatcher, SMTP and SES, Markdown/HTML/MJML templates and versions, brands and the layout, preview, EmailService, the process, the core bridge, PHI refusal and redaction, the app home | GreenMail SMTP sink end to end; SES through a mocked `SesV2Client`; the claim race (two runners, each message sent once); retry, dead letter, idempotency; rendering (variables, escaping, `#if`/`#each`, plain text, dark-mode contrast); PHI refusal; snapshot redaction; the canary test; producer standards (names, nav, permissions) |
| (b) | Postmark, SendGrid, Mailgun, Gmail SMTP | HTTP mocks per provider (success, 4xx permanent, 429/5xx transient); the Gmail preset. HTML/MJML moved into part (a), part 2 at owner request. |
| (c) | webhook route and inbox, events, suppression, unsubscribe (Preferences deferred) | signature verification per provider (valid, tampered, stale, missing secret); the SNS certificate host rule; suppression on hard bounce and complaint; one-click POST and the GET page; category rules |
| (d) | consumer migrations | each consumer's own tests; a contract test of `EmailService` |
| (e) | inbound | design only for now |

Dependencies: phase (a) builds on QQQ 4.1.0-RC.5 with the classification stand-in, and moves to
later RCs for `DataClassification` and Preferences when their APIs are released.

## 16. Owner steps (outside code)
- **PMG (kof22):** none; the relay already accepts the cluster.
- **SES (when a consumer adopts it):** create the SES identity for each sending domain in the
  `terraform/dns-route53` root (owner-applied), request production access, and create a
  configuration set with an SNS destination to `https://<app>/qqq/hooks/email/ses/<provider>`.
  The DNS records (DKIM CNAMEs, a custom MAIL FROM subdomain with its MX and SPF; DMARC stays at
  p=reject) go in as Praetor octoDNS PRs once the identity exists, because SES issues the DKIM
  tokens.
- **Postmark, SendGrid, Mailgun, Gmail:** accounts and keys only if used; keys go into Infisical
  under the env names the provider rows reference.
- **Keys:** `QQQ_EMAIL_UNSUBSCRIBE_KEY` and `QQQ_EMAIL_BODY_KEY` per deployment (Infisical, seeded
  write-only like other app keys).
