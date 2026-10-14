# Durable job email effects

This implements the email-side contract for QRun-IO/qqq#1191, spec section 2.13,
without importing core jobs types. QQQ stays at released 4.1.0-RC.5. The
`EmailJobEffectTarget implements EffectTarget` binding is deferred until RC.7 ships.

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

`EmailEffects.send(request, effectIdempotencyKey)` sets the request's key, refusing
a different pre-existing key, a missing/blank/overlength effect key, a null request
or a caller transaction. The service performs its usual validation and send
permission checks. Success is returned only after the outbox transaction commits;
there is no transport call here. Use ordinary `EmailService.send` for caller-owned
or cross-backend deferred transactions.

`EmailEffects.Result(outcome, message)` contains an `EmailEffectOutcome` and a
nullable `EmailMessageRef(Integer id, String status, String rfcMessageId,
String providerMessageId)`:

- `QUEUED_OR_DUPLICATE`: one committed row accepted the handoff, including replays.
- `REFUSED`: invalid or suppressed request. A suppressed audit row has a reference;
  refusals without a stored row have a null reference. Replaying a suppressed row
  stays refused even though `EmailService` returns `DUPLICATE`.
- Database/query failures throw `QException`; callers must retry or reconcile,
  never turn an uncertain outcome into a permanent refusal.

`findByIdempotencyKey(key)` and `findByRfcMessageId(messageId)` return
`Optional<EmailMessageRef>` from system reads, like other internal outbox reads.
They return empty for null/blank/missing values. These are trusted backend APIs,
not viewer-scoped public endpoints. Lookups reflect current status and provider
reference and do not expose bodies, recipients or variables.

The effect's `SENT` means successful durable handoff, not mailbox delivery. The
email dispatcher owns later delivery/retry failures. Reconcile acknowledges an
existing outbox row (including an audit row); it must never create another email
merely because that row is terminal. Retain message rows and idempotency keys for
at least the maximum effect retry/reconciliation horizon.

## RC.7 binding design note (not compiled)

Mapping: `deliver -> QUEUED_OR_DUPLICATE => SENT(ref.id)`, `REFUSED => FAILED`;
`reconcile -> findByIdempotencyKey present => FOUND(ref.id), absent => NOT_FOUND`.
Exceptions escape to core's retry/unknown handling. Payload decoding must preserve
the existing PHI/variable policies and must not attach a caller transaction.

This 25-line sketch is documentation-only. Match core names and signatures to the
published RC.7 contract when it ships; `decode` represents payload conversion.

```java
// Future RC.7 binding; no core jobs imports in the RC.5 implementation.
final class EmailJobEffectTarget implements EffectTarget
{
   // SENT acknowledges the committed qbit outbox row.
   @Override
   public DeliveryResult deliver(Effect effect) throws Exception
   {
      EmailRequest request = decode(effect.payload());
      EmailEffects.Result result = EmailEffects.send(request, effect.idempotencyKey());
      if(result.outcome() == EmailEffectOutcome.REFUSED)
      {
         return (DeliveryResult.failed("Email refused"));
      }
      return (DeliveryResult.sent(result.message().id().toString()));
   }

   // Existence reconciles handoff independently of mailbox delivery.
   @Override
   public ReconcileResult reconcile(Effect effect) throws Exception
   {
      return (EmailEffects.findByIdempotencyKey(effect.idempotencyKey())
         .map(ref -> ReconcileResult.found(ref.id().toString()))
         .orElseGet(ReconcileResult::notFound));
   }
}
```

Tests use H2, the existing GreenMail SMTP sink, fake SES and HTTP request seams;
no vendor endpoints are called. The PostgreSQL migration is supplied for host
deployment and is not executed by the offline Java test gate.
