# QBit Email

One email service for a QQQ system: an outbox with async sending, retries, dead letters and
idempotency keys; providers as data rows (SMTP relay, Amazon SES, Postmark, SendGrid, Mailgun,
Gmail); Markdown and MJML/HTML templates with versions and branding; delivery tracking from
provider webhooks; a suppression list; RFC 8058 one-click unsubscribe; and PHI-safe sending.
Inbound mail is phase 2.

Design: [`docs/DESIGN.md`](docs/DESIGN.md).

## Requirements

- Java 21
- QQQ 4.1 (`qqq-bom-pom` 4.1.0-RC.4 or later)
- An RDBMS backend for the `email_` tables

## Install

```xml
<dependency>
   <groupId>com.kingsrook.qbits</groupId>
   <artifactId>qbit-email</artifactId>
   <version>${qbit-email.version}</version>
</dependency>
```

```java
new EmailQBitProducer()
   .withQBitConfig(new EmailQBitConfig().withBackendName("rdbms"))
   .produce(qInstance);
```

## Tables, permissions and configuration

See [`docs/DESIGN.md`](docs/DESIGN.md). Tables are prefixed `email_`; permissions use the full
qbit name (`email.view`, `email.send`, `email.manage`, `email.admin`).

## Adopting in another app

Add the dependency, produce the qbit against your RDBMS backend, add a provider row, and send
through `EmailService` (or the `email_send_templated` process). Code that already calls core's
`SendEmailAction` can route through the outbox with the messaging bridge (DESIGN section 2).

## Build

```bash
./mvnw verify    # tests, Checkstyle and the JaCoCo gate
```

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
