# Email providers

Create an enabled `email_provider` row and choose exactly one enabled default.
Provider edits and Send Test Email require `email.admin`. Test sends queue through
the outbox and obey the same rate limits as application messages.

Field names here are database columns. QQQ record/API names are camelCase:
`secret_env_name` is `secretEnvName`, for example. All kinds use `name`, `label`,
`kind`, `enabled`, `is_default`, `from_address`, optional `from_name` / `reply_to`,
and optional `max_per_minute` / `max_per_day`. When set, `sending_domain` must
match the From address domain exactly.

## Credentials and delivery

Credential fields contain environment variable **names**, never values. Names
must match `^[A-Z_][A-Z0-9_]{0,99}$` and the effective allowlist. The defaults
below supplement `EmailQBitConfig.allowedEnvNames`; explicitly allow custom names.
Allowlisting does not automatically select a name for a row.

| Kind | `username_env_name` | `secret_env_name` | `webhook_secret_env_name` |
|---|---|---|---|
| SMTP | `EMAIL_SMTP_USERNAME` (optional) | `EMAIL_SMTP_PASSWORD` | Not used |
| SES | `AWS_ACCESS_KEY_ID` (optional) | `AWS_SECRET_ACCESS_KEY` (optional pair) | Not used; `sns_topic_arn` is a plain ARN |
| POSTMARK | Not used | `POSTMARK_SERVER_TOKEN` | `POSTMARK_WEBHOOK_PASSWORD` |
| SENDGRID | Not used | `SENDGRID_API_KEY` | `SENDGRID_WEBHOOK_PUBLIC_KEY` |
| MAILGUN | Not used | `MAILGUN_API_KEY` | `MAILGUN_WEBHOOK_SIGNING_KEY` |
| GMAIL_SMTP | `GMAIL_USERNAME` | `GMAIL_APP_PASSWORD` | Not used |

Legacy `EMAIL_POSTMARK_SERVER_TOKEN`, `EMAIL_POSTMARK_WEBHOOK_PASSWORD`,
`EMAIL_SENDGRID_API_KEY`, `EMAIL_SENDGRID_WEBHOOK_PUBLIC_KEY`, `EMAIL_MAILGUN_API_KEY`,
`EMAIL_MAILGUN_SIGNING_KEY`, `EMAIL_GMAIL_USERNAME` and `EMAIL_GMAIL_APP_PASSWORD`
remain allowlisted for their kinds. SES also allowlists `AWS_SESSION_TOKEN` for
the default credential chain; explicit row credentials are an access/secret pair.

Values resolve through `secretResolver` (default `System::getenv`) for each send
attempt. Rotation needs no service restart. Provider record presence indicators
also resolve values, returning only Set / Not set; enqueue and internal queries
do not resolve credentials. Values and provider response diagnostics are never
logged, stored in errors, returned in exceptions or cached by these transports.

HTTP uses fixed HTTPS endpoints, a 10-second connect timeout, a 30-second request
timeout, no redirects and no client retries. The outbox owns retries. MIME and
multipart bodies are built entirely in memory; services use no filesystem storage.

HTTP 429 and 5xx are transient. Retry-After accepts seconds or an HTTP date;
the dispatcher caps explicit delays at one hour. Authentication/account failures
mark the provider UNHEALTHY, stop its remaining deliveries for the dispatch pass,
and retain messages for retry without consuming their failure budget. Permanent
message errors become DEAD_LETTER. Exceptions use fixed text and numeric codes;
stored errors use fixed messages, never response bodies.

Acceptance means SENT, not delivered. Delivery is at least once: a crash after
acceptance can cause a duplicate. Limits count attempts across replicas in rolling
minute and 24-hour windows, not recipients or successful deliveries. Leave room
for retries, recipient quotas and other applications using the same account.

## SMTP

Set `kind=SMTP`, `host`, `port`, and `security=NONE`, `STARTTLS`, or `TLS`. For
authenticated SMTP, set both credential names; for an unauthenticated relay,
leave both empty. Authentication requires encryption except for loopback or an
explicit host `allowPlaintextSmtpAuth` configuration.

Configure the relay's allowed sender domains and route. For public delivery,
verify the domain with the operator, publish its DKIM and SPF records, and align
the From domain with DMARC. Update the existing SPF policy rather than adding a
second SPF record. Set limits below the relay's quota. SMTP has 10-second connect
and 15-second read/write timeouts; there is no provider event webhook here.

## Amazon SES

Set `kind=SES`, `region`, and a verified `from_address`. Optionally set
`sending_domain`, `configuration_set` and `sns_topic_arn`. Leave credential names
empty for the AWS default chain (workload/instance role or AWS environment);
otherwise supply both access-key and secret-key environment names.

Verify the sender identity in the sending region and publish SES's DKIM records.
Configure SPF for a custom MAIL FROM domain if used. Request production access
when leaving the sandbox. Configure the configuration set's SNS event destination
and put the topic ARN in `sns_topic_arn`, not a secret field.

Sending quotas are regional and account-specific, with sandbox, recipient and
rate restrictions. Set both rate fields to fit those quotas. SES sends raw MIME
with explicit envelope destinations and strips Bcc from its MIME headers.

## Postmark

Set `kind=POSTMARK`, `secret_env_name=POSTMARK_SERVER_TOKEN`, a verified
`from_address`, and optionally `sending_domain`. Use a server token, not an
account token. `message_stream` defaults to `outbound`; another transactional or
broadcast stream must exist on that server. `track_opens` and `track_clicks`
default to false; click tracking maps to HtmlAndText or None.

Verify the domain or sender signature, publish Postmark's DKIM and recommended
Return-Path/SPF DNS records, obtain account approval and create the desired
message stream. The endpoint is `https://api.postmarkapp.com/email`;
the returned `MessageID` is retained.

| Signal | Outcome |
|---|---|
| ErrorCode 10 | Provider failure: bad token |
| ErrorCode 300 / 406 | Permanent: invalid request / inactive recipient |
| ErrorCode 400/401, 405, 412, 413, 422 | Provider failure: signature, sending approval or server/account configuration |
| ErrorCode 1235/1236 / 1480 | Provider failure: stream / IP configuration |
| HTTP 401/403 | Provider failure |
| HTTP 429 / 5xx | Transient; takes precedence over a body code |
| Other request errors | Permanent; malformed successful responses are transient |

API ErrorCode 413 is an account problem; HTTP 413 is a payload-size error.
Check plan, recipient and payload limits before setting the rate fields. The
single-message API documents a 10 MB message limit. See
[Postmark API errors and limits](https://postmarkapp.com/developer/api/overview).

## SendGrid

Set `kind=SENDGRID`, `secret_env_name=SENDGRID_API_KEY`, a verified `from_address`
and optionally `sending_domain`. Create a key with Mail Send permission.
Authenticate the domain and publish SendGrid's DKIM/SPF DNS records; single-sender
verification can be used for initial tests. Confirm the account can send.

`track_opens` controls open tracking; `track_clicks` controls HTML and plain-text
click tracking. Explicit false values override account tracking defaults.
The global endpoint is `https://api.sendgrid.com/v3/mail/send`; only HTTP 202
counts as success and `X-Message-Id` becomes the provider message ID.

One personalization contains To/Cc/Bcc with display names. `custom_args` carries
`qqq_email_id`. Reserved headers are filtered case-insensitively: `x-sg-id`,
`x-sg-eid`, `received`, `dkim-signature`, `Content-Type`, `Content-Transfer-Encoding`,
`To`, `From`, `Subject`, `Reply-To`, `CC`, `BCC`. `List-Unsubscribe` and
`List-Unsubscribe-Post` are retained.

HTTP 401/403 are provider failures, 400/413 permanent, and 429/5xx transient.
Set rate limits for the account's plan/API quota. Payload and recipient limits
apply separately from outbox attempt limits. See
[SendGrid Mail Send](https://www.twilio.com/docs/sendgrid/api-reference/mail-send/mail-send).

## Mailgun

Set `kind=MAILGUN`, `secret_env_name=MAILGUN_API_KEY`, `sending_domain` (required,
a DNS name without a scheme/path), and `region=US` or `EU` (required).
The From address domain must match the sending domain.

Add and verify the domain in that region. Publish Mailgun's SPF/DKIM records and
its requested tracking DNS records if enabling tracking. Use an API key allowed
to send for that domain. Sandbox domains restrict recipients; check production
status and plan/rate quotas before choosing `max_per_minute` / `max_per_day`.
Those fields do not enforce MIME-size or recipient limits.

The endpoint is `https://api.mailgun.net/v3/{sending_domain}/messages.mime`, or
`https://api.eu.mailgun.net/v3/{sending_domain}/messages.mime` for EU. Basic auth
uses `api:<resolved key>`. Multipart includes all To/Cc/Bcc mailboxes as envelope
destinations, MIME `message` with Bcc stripped, `o:tracking-opens`,
`o:tracking-clicks` (explicit yes/no from the row), and `v:qqq_email_id`.
The response `id` is stored without surrounding angle brackets.

HTTP 401/403 are provider failures, 400 permanent, and 429/5xx transient.

## Gmail SMTP

| Field | Value |
|---|---|
| `kind` | `GMAIL_SMTP` |
| `username_env_name` | `GMAIL_USERNAME`: value is the full account email address |
| `secret_env_name` | `GMAIL_APP_PASSWORD`: value is the generated app password |
| `security` | `STARTTLS` |
| `from_address` | Account address or a permitted configured alias |
| `max_per_day` | At most about 500 personal / 2000 paid Workspace; lower with headroom |

The preset always uses `smtp.gmail.com:587` with required STARTTLS, ignoring
custom host/port values. Both env names are required; other security values are
refused even if plaintext auth is enabled for other relays. An omitted security
value uses the preset at the settings layer, but provider forms default to NONE,
so select STARTTLS when creating the row.

Enable 2-step verification and generate an app password for the service; do not
use the normal account password. Account/admin security policy may disable app
passwords. A Google password change revokes existing app passwords, requiring a
new environment value. See
[Google app password setup](https://support.google.com/accounts/answer/185833).

Google manages domain authentication for personal gmail.com senders. For
Workspace, verify the domain and configure Google's SPF/DKIM records and DMARC.
Trials, new accounts, SMTP recipient quotas and other account users can reduce
available capacity. The 500/2000 limit is an upper planning bound, not guaranteed
throughput. See [Workspace limits](https://support.google.com/a/answer/166852)
and [personal Gmail limits](https://support.google.com/mail/answer/22839).

`GMAIL_API` is refused with:
`Gmail API sending is specified but not built; use GMAIL_SMTP`.
It would require a Google Cloud project, OAuth consent, client credentials and
refresh-token management. It adds no needed system-mail capability over the app
password preset in this phase and is built only on request (DESIGN 3.6).

## Event webhooks

Phase (c) installs authenticated provider receivers when the host includes Javalin.
Use `https://<app>{base}/{kind}/{providerName}`, where `base` is
`EmailQBitConfig.webhookBasePath` (default `/qqq/hooks/email`), `kind` is `ses`,
`postmark`, `sendgrid` or `mailgun`, and `providerName` is the row's unique name.
The row must be enabled and its kind must match. The configurable base must start
with `/`, have no trailing slash, and stay outside `/qqq/v1`.

| Provider | Setup and events |
|---|---|
| SES | Send configuration-set Delivery, Bounce, Complaint, Reject and DeliveryDelay events to SNS; enable Open/Click only when desired. Set `sns_topic_arn` to that exact topic. Subscribe the HTTPS `/ses/{providerName}` URL with raw delivery **off**, so SNS sends its signed JSON envelope. No webhook secret env is used. Verified subscription confirmations are fetched automatically and retained as IGNORED inbox rows. |
| Postmark | Configure server/message-stream webhooks for Delivery, Bounce, SpamComplaint and SubscriptionChange; optionally Open and Click. Use HTTP Basic username **`postmark`** and the password resolved from `webhook_secret_env_name` (normally `POSTMARK_WEBHOOK_PASSWORD`). Configure those credentials in Postmark's webhook settings/URL; keep the stored provider row limited to the environment name. |
| SendGrid | Enable the Signed Event Webhook for delivered, bounce, dropped, deferred, spamreport and unsubscribe (group_unsubscribe is also accepted); optionally open/click. Store the base64 DER verification public key in `SENDGRID_WEBHOOK_PUBLIC_KEY`, referenced by `webhook_secret_env_name`. Preserve both Twilio signature/timestamp headers through the proxy. Batches are authenticated over timestamp plus the unchanged raw body. |
| Mailgun | Configure regional domain webhooks for delivered, permanent/temporary failed, complained and unsubscribed; optionally opened/clicked. Set `webhook_secret_env_name=MAILGUN_WEBHOOK_SIGNING_KEY` to the account's HTTP webhook signing key, **not** its sending API key. Preserve the JSON `signature` object and `event-data`. |
| SMTP / Gmail SMTP | No native event receiver. One-click unsubscribe remains available for opt-out mail. |

SNS accepts SignatureVersion 1 and 2, pins TopicArn, and fetches certificates only
from HTTPS `sns.<region>.amazonaws.com` or `.amazonaws.com.cn` hosts with `.pem`
paths. Certificates are cached only in memory; redirects are disabled. Confirmation
URLs use the same HTTPS host restriction. Canonicalization follows
[AWS's signature rules](https://docs.aws.amazon.com/sns/latest/dg/sns-verify-signature-of-message-verify-message-signature.html).
SNS retries can be old, so freshness is enforced only for SendGrid and Mailgun:
timestamps must be within five minutes in either direction. Keep host clocks synchronized.
Postmark authenticates the request with Basic auth and supplies no signed timestamp.

Requests are capped at 1 MiB, including chunked requests. Responses are JSON:
200 `received` or `duplicate`, 401 invalid verification/Mailgun nonce replay,
404 missing/disabled/mismatched provider, 413 oversized input, and 503 unavailable
verification configuration, SNS fetch or database. No request bodies, recipient
addresses or credential values are logged. Raw payloads are stored in the DB inbox
as sensitive data, readable with `email.view`.

The hidden `email_process_webhooks` schedule runs every 30 seconds. One bounded
transaction applies each inbox row, including events, message state and suppressions.
The common provider lock serializes suppression upserts across replicas/providers;
no network I/O occurs under that lock. Failed attempts back off with `retryMinutes`
and become FAILED after six attempts. Use the inbox's **Reprocess** Review/Result
action (`email.manage`) after resolving the cause. Unmatched messages retry, including
a webhook racing the transport's SENT commit. Unknown/disabled tracking events are
IGNORED. Per-event recipient keys prevent overlapping SendGrid batches from duplicating
history. Mailgun's `(provider_id, nonce)` uniqueness is distinct from event deduplication:
a stored nonce is refused with 401 even if its event is already known.

Hard bounces and complaints upsert ALL suppression; complaints take precedence over
hard-bounce reasons. Unsubscribe events suppress only MARKETING or NOTIFICATION for
the matched message. Delivery advances SENT to DELIVERED; hard bounce and complaint
advance delivery state without reversing a later complaint. Opens/clicks require the
corresponding provider flag and are never retained for PHI-approved messages.
Provider reason text is address-masked and limited to 500 characters; only clicks
retain URLs. More than three complaints in 24 hours raises optional WARNING alert
`email.complaint`, deduplicated per UTC day by core's alert sink.

## One-click unsubscribe

Set `publicBaseUrl` to the public HTTPS app origin and supply
`QQQ_EMAIL_UNSUBSCRIBE_KEY` through the secret resolver. The configured webhook base
also controls newly signed unsubscribe URLs. Keep the key and published path stable:
tokens have no expiry, but cannot resolve after their message row is deleted.

GET `{base}/unsubscribe/{token}` displays a self-contained, escaped light/dark form
and never changes state. POST `List-Unsubscribe=One-Click` returns an empty 200;
the page's form POST returns confirmation HTML. Both atomically upsert category
suppression and record an UNSUBSCRIBED event. Repeated POSTs do not duplicate events.
Invalid tokens return a generic 404. TRANSACTIONAL and SECURITY tokens are neither
issued nor accepted.

The existing v1 token binds the message, category and **complete To/Cc/Bcc envelope
hash**, rather than one mailbox. Following that link opts out every mailbox in the
bound envelope for that category; the page reveals none of those addresses. Send
one recipient per opt-out message when individual preference control is needed.
Preferences UI remains deferred until core #1162 ships in an RC.
