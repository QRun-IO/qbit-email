# Durable job email effects

QQQ 4.1.0-RC.7 supplies the durable jobs side-effects outbox. QBit Email registers
`com.kingsrook.qbits.email.outbox.EmailJobEffectTarget` as target `email` to hand
those effects to its own committed outbox. All provider sending remains asynchronous.

## Stored identity and schema upgrade

`email_message.rfc_message_id` (`rfcMessageId` in metadata) is a unique STRING(500)
shown in the message T2 details. Before insertion, the service stores
`<token@domain>`, where `token` is the first 32 lowercase hex characters of SHA-256
of the UTF-8 idempotency key. Without a key, the seed is `qbit-email:` plus a fresh
UUID, since the row id is not yet allocated. The domain is the normalized From
mailbox domain, excluding display names; unavailable or unsuitable domains use
`qbit-email.local`. The key itself is never included in the header.

The dispatcher reads this stored value on every attempt. JavaMail's
`updateMessageID()` is overridden so repeated `saveChanges()` calls cannot replace
it. A manual **Resend** creates a new outbox row and a new identity; a delivery retry
keeps its identity. `providerMessageId` remains the provider's response identifier,
distinct from `rfcMessageId` and the numeric outbox `id`.

New installations use [email-postgres.sql](ddl/email-postgres.sql). Existing
PostgreSQL installations must pause enqueue and dispatch, then apply
[email-message-id-upgrade.sql](ddl/email-message-id-upgrade.sql) before starting
this version. The migration backfills old rows using the existing key, or
`qbit-email:` plus the already allocated row id, and the stored From domain.
It cannot recover previously generated random transport Message-IDs. Review host
schema adaptations first; host security columns are unaffected.

## Provider behavior

| Transport | Request mechanism | Identity preservation |
|---|---|---|
| SMTP / Gmail SMTP preset | MIME `Message-ID` | Sent unchanged by this transport; downstream relay policy still applies |
| SES v2 | Raw MIME `Message-ID` | **SES replaces Message-ID downstream** |
| Postmark | `Headers: [{Name: "Message-ID", Value: "<...>"}]` | Custom Message-ID supported |
| SendGrid | `headers: {"Message-ID": "<...>"}` | Sent as a custom header |
| Mailgun | MIME Message-ID plus multipart `h:Message-Id` | Sent in the `/messages.mime` request |

All transports retain `X-QQQ-Email-Id` with the numeric outbox id. Postmark's
`MessageID`, SendGrid's response `X-Message-Id`, and SES's returned id are provider
references, not our RFC header. A fixed header enables recognition and receiver
deduplication where supported, but is not an exactly-once inbox guarantee.

Provider references: [Postmark custom Message-ID](https://postmarkapp.com/developer/user-guide/send-email-with-api/send-a-single-email),
[SendGrid custom headers](https://www.twilio.com/docs/sendgrid/api-reference/mail-send/mail-send),
[Mailgun sending parameters](https://documentation.mailgun.com/docs/mailgun/api-reference/send/mailgun/messages/post-v3--domain-name--messages),
and [SES header replacement](https://docs.aws.amazon.com/ses/latest/APIReference/API_SendRawEmail.html).

## Handoff and reconcile

`EmailApi.sendOnce(EmailApiRequest request, String effectKey)` sets the request's key, refusing
a different pre-existing key, a missing/blank/over-500-character effect key, a null request
or a caller transaction. The service performs its usual validation and send
permission checks. Success is returned only after the outbox transaction commits;
there is no transport call here. Use ordinary `EmailApi.send` with
`request.withTransaction(transaction, backendName)` for caller-owned or cross-backend
deferred transactions. `sendOnce` refuses either kind of caller transaction.

`EmailApiEffectResult(outcome, message)` contains an `EmailApiEffectOutcome` and a
nullable `EmailApiMessage` with the outbox id, current delivery status, RFC Message-ID,
provider reference and other metadata. All these types are in
`com.kingsrook.qbits.email.api`, the sole public qbit-email surface:

- `QUEUED_OR_DUPLICATE`: one committed row accepted the handoff, including replays.
- `REFUSED`: invalid or suppressed request. A suppressed audit row has a reference;
  refusals without a stored row have a null reference. Replaying a suppressed row
  stays refused even though `EmailService` returns `DUPLICATE`.
- Database/query failures throw `QException`; callers must retry or reconcile,
  never turn an uncertain outcome into a permanent refusal.

`EmailApi.getMessageByIdempotencyKey(key)` and `EmailApi.getMessageByRfcMessageId(messageId)`
return `Optional<EmailApiMessage>` from system reads of an explicit metadata projection.
They return empty for null/blank/missing values. These are trusted backend APIs,
not viewer-scoped public endpoints. Lookups reflect current status and provider
reference and do not expose bodies, recipients or variables. `EmailApi.getMessage(id)`
and `EmailApi.findMessagesByIdempotencyKeyPrefix(prefix, limit)` return the same projection,
including `rfcMessageId`. Callers must authorize exposing this metadata to their users.
`EmailEffects`, `EmailEffectOutcome` and `EmailMessageRef` are deprecated internal
implementation types; consuming qbits use `EmailApi`.

The effect's `SENT` means successful durable handoff, not mailbox delivery. The
email dispatcher owns later delivery/retry failures. Reconcile acknowledges an
existing outbox row (including an audit row); it must never create another email
merely because that row is terminal. Retain message rows and idempotency keys for
at least the maximum effect retry/reconciliation horizon.

## Register, declare and emit

Enable durable jobs **before** producing the email qbit, so the producer can register
its target automatically:

```java
instance.withSupplementalMetaData(new DurableJobsMetaData());
new DurableJobsMetaDataProvider().defineAll(instance, "rdbms", null);
new EmailQBitProducer()
   .withQBitConfig(new EmailQBitConfig().withBackendName("rdbms"))
   .produce(instance);
```

Apply core's jobs database schema as usual. `EmailJobEffectTarget.register(instance)`
is a no-op without `DurableJobsMetaData`. If jobs are enabled after producing email,
call this helper explicitly after enabling them.

Declare the effect on the process's `DurableProcessMetaData` (retain any existing
durable settings). Replace `<step>` with the process's real backend step name:

```java
process.withSupplementalMetaData(new DurableProcessMetaData()
   .withEffect(new JobEffectDeclaration("welcomeEmail", "<step>", "email",
      JobEffectType.IDEMPOTENT_EXTERNAL)));
```

Then emit from that step using its `RunBackendStepInput input` and a stable row key:

```java
Map<String, Serializable> payload = new LinkedHashMap<>();
payload.put("to", "person@example.com");
payload.put("subject", "Welcome");
payload.put("textBody", "Your account is ready.");
JobEffects.emit(input, "welcomeEmail", rowKey, payload);
```

The jobs classes above come from `com.kingsrook.qqq.backend.core.jobs` and its
`effects` package; `Map`, `LinkedHashMap` and `Serializable` are JDK types.
Core stores the effect with the chunk's progress and dispatches after commit.
Outside a durable job, `JobEffects.emit` calls the target immediately with a fresh
key, still only enqueueing email; a result other than SENT throws.

Use **IDEMPOTENT_EXTERNAL**: qbit-email deduplicates by the effect's exact key,
while its own delivery outbox reuses the stored Message-ID on provider attempts.
This acknowledges one durable handoff, not exactly-once delivery to a mailbox.

## Payload contract

Unknown keys are ignored. Recognized values must have the following types:

| Keys | Values |
|---|---|
| `to`, `cc`, `bcc` | A String mailbox or a list of String mailboxes; omitted means empty. Comma-separated strings are not split. |
| `subject`, `templateName` | Strings; ordinary template/ad-hoc validation applies. |
| `markdownBody`, `htmlBody`, `textBody` | String body alternatives. |
| `variables` | Map with String keys and serializable values; requires a template schema and retains the normal PHI/classification checks. |
| `category` | String enum name: `TRANSACTIONAL`, `SECURITY`, `NOTIFICATION`, or `MARKETING`. |
| `providerName`, `brandName`, `locale` | Strings; omitted locale retains `en`. |
| `from`, `replyTo` | String mailboxes, subject to ordinary sender validation. |
| `fromAddressId` | Integer identifying an enabled address for the selected provider. |
| `sendAfter` | ISO-8601 Instant String, for example `2026-10-14T12:00:00Z`. |
| `linkedTableName`, `linkedRecordId` | String table name; record id converted to String. |

The target always sets `senderQbit = "jobs"`, `senderProcess = effectName`, and
uses `EffectRequest.idempotencyKey()`. Payload keys cannot override these or attach
a transaction. `recordVariables`, `securityValues` and `payloadRef` are not decoded;
provide the supported values directly in `payload`.

## Identity and outcomes

The dispatcher invokes the target as the system user. When `jobId` is present, the
target loads the job under core's system scope and builds its owner session through
`JobSessions.forJob`. Sending runs inside `QContext.withTemporaryContext` using that
owner session, with current permissions and the intersection of current and captured
security keys. The original dispatcher context is restored even after an exception.
Without a job id, sending retains the caller's session.

- `QUEUED_OR_DUPLICATE` becomes `EffectResult.sent(outboxId)`; `providerRef` is the
  String email row id, not the transport provider's reference.
- `REFUSED` becomes `EffectResult.failed(reasons)`, including suppressed replays.
- A missing job or an owner session rejected by authentication/permission validation
  becomes FAILED with `The job's owner can no longer send it: ...`.
- Other exceptions, including payload decoding and database failures, become UNKNOWN.
  Error text omits raw exception details to avoid exposing payloads or database values.
  Core may resend IDEMPOTENT_EXTERNAL effects using the same key.
- Reconcile uses `EmailEffects.findByIdempotencyKey`: any existing row becomes FOUND
  with its id, otherwise NOT_FOUND. Lookup failures propagate rather than falsely
  reporting absence. Reconcile never creates mail or changes the owner session.

The binding uses `EmailEffects` internally; other consuming qbits continue to use
`EmailApi`. The H2 target tests install core jobs metadata with `defineAll`, create
all columns of the `jobs_job` lookup table, and observe the owner through an insert
customizer because email messages have no built-in created-by/user field. No
production job-lookup test seam is needed.

Tests use H2, the existing GreenMail SMTP sink, fake SES and HTTP request seams;
no vendor endpoints are called. The PostgreSQL migration is supplied for host
deployment and is not executed by the offline Java test gate.
