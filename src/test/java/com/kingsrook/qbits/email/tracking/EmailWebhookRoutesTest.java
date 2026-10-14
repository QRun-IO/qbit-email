/*
 * QQQ - Low-code Application Framework for Engineers.
 * Copyright (C) 2021-2025.  Kingsrook, LLC
 * 651 N Broad St Ste 205 # 6917 | Middletown DE 19709 | United States
 * contact@kingsrook.com
 * https://github.com/Kingsrook/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kingsrook.qbits.email.tracking;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.render.EmailUnsubscribeLinks;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.webhook.EmailWebhookRouteProvider;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;
import com.kingsrook.qqq.middleware.javalin.QJavalinMetaData;
import io.javalin.Javalin;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.kingsrook.qbits.email.tracking.TrackingTestSupport.*;
import static org.assertj.core.api.Assertions.*;

/*******************************************************************************
 ** Real random-port HTTP exercises the exact route provider and database boundary.
 *******************************************************************************/
class EmailWebhookRoutesTest
{
   private Javalin server;
   private HttpClient client;
   private QInstance instance;
   private final List<String> fetched = new java.util.concurrent.CopyOnWriteArrayList<>();
   private static final String BASE = "/custom/email";

   /*******************************************************************************
    ** Test the configurable path and injected SNS HTTP seam.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      instance = H2Support.setup(c -> c.withWebhookBasePath(BASE).withPublicBaseUrl("https://app.example.com").withSecretResolver(name -> SECRET));
      EmailRuntime.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
      String pem = resource("/sns-test/test-only-certificate.pem");
      var routes = new EmailWebhookRouteProvider(new SnsVerifier(request ->
      {
         fetched.add(request.uri().toString());
         return (response(request, 200, request.uri().getPath().endsWith(".pem") ? pem : "confirmed"));
      }));
      routes.setQInstance(instance);
      server = Javalin.create(routes::acceptJavalinConfig).start(0);
      client = HttpClient.newHttpClient();
   }

   /*******************************************************************************
    ** Release every server, connection and thread-local test context.
    *******************************************************************************/
   @AfterEach
   void teardown()
   {
      server.stop();
      client.close();
      EmailRuntime.setClock(null);
      QContext.clear();
   }

   /*******************************************************************************
    ** HTTP request helper, with no application code bypass.
    *******************************************************************************/
   private HttpResponse<String> request(String path, String method, String body, String... headers) throws Exception
   {
      var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + BASE + path));
      if(headers.length > 0)
      {
         builder.headers(headers);
      }
      builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
      return (client.send(builder.build(), HttpResponse.BodyHandlers.ofString()));
   }

   /*******************************************************************************
    ** Fixed Basic auth used only by synthetic Postmark requests.
    *******************************************************************************/
   private static String auth()
   {
      return ("Basic " + Base64.getEncoder().encodeToString(("postmark:" + SECRET).getBytes(StandardCharsets.UTF_8)));
   }

   /*******************************************************************************
    ** All status codes and streaming size caps before any authentication lookup.
    *******************************************************************************/
   @Test
   void postmarkStatusesAndDedup() throws Exception
   {
      QRecord provider = provider("POSTMARK");
      String body = resource("/webhooks/postmark-delivery.json");
      assertThat(request("/postmark/postmark", "POST", body, "Authorization", auth()).body()).contains("received");
      assertThat(request("/postmark/postmark", "POST", body, "Authorization", auth()).body()).contains("duplicate");
      assertThat(H2Support.all(EmailTables.INBOX)).hasSize(1);
      assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
      assertThat(request("/postmark/postmark", "POST", body).statusCode()).isEqualTo(401);
      assertThat(request("/postmark/postmark", "POST", "invalid", "Authorization", auth()).statusCode()).isEqualTo(401);
      assertThat(request("/mailgun/postmark", "POST", body).statusCode()).isEqualTo(404);
      assertThat(request("/postmark/missing", "POST", body).statusCode()).isEqualTo(404);
      assertThat(request("/postmark/postmark", "POST", "x".repeat(1024 * 1024 + 1)).statusCode()).isEqualTo(413);
      var chunked = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + BASE + "/postmark/postmark"))
         .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(new byte[1024 * 1024 + 1]))).build();
      assertThat(client.send(chunked, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(413);
      EmailRuntime.config().withSecretResolver(name -> null);
      assertThat(request("/postmark/postmark", "POST", body).statusCode()).isEqualTo(503);
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id")).withValue("enabled", false).withValue("isDefault", false));
      assertThat(request("/postmark/postmark", "POST", body).statusCode()).isEqualTo(404);
   }

   /*******************************************************************************
    ** Missing per-provider verification configuration is explicitly retryable.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(strings = {"SES", "SENDGRID", "MAILGUN"})
   void missingSecretOrTopic(String kind) throws Exception
   {
      QRecord provider = provider(kind);
      if("SES".equals(kind))
      {
         SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", provider.getValue("id")).withValue("snsTopicArn", null));
      }
      EmailRuntime.config().withSecretResolver(name -> null);
      assertThat(request("/" + kind.toLowerCase() + "/" + kind.toLowerCase(), "POST", "{}").statusCode()).isEqualTo(503);
   }

   /*******************************************************************************
    ** DB failure is 503, and idempotent registration never adds duplicate routes.
    *******************************************************************************/
   @Test
   void unavailableStoreAndRegistration() throws Exception
   {
      provider("POSTMARK");
      EmailWebhookRouteProvider.register(instance);
      EmailWebhookRouteProvider.register(instance);
      assertThat(QJavalinMetaData.ofOrWithNew(instance).getAdditionalRouteProviderReferences()).hasSize(1);
      var removed = instance.getTables().remove(EmailTables.INBOX);
      try
      {
         assertThat(request("/postmark/postmark", "POST", resource("/webhooks/postmark-delivery.json"), "Authorization", auth()).statusCode()).isEqualTo(503);
      }
      finally
      {
         instance.addTable(removed);
      }
   }

   /*******************************************************************************
    ** SNS verifies before confirming, with a durable ignored control message.
    *******************************************************************************/
   @Test
   void snsNotificationConfirmationAndTopicMismatch() throws Exception
   {
      provider("SES");
      EmailRuntime.setClock(Clock.fixed(certificateTime(), ZoneOffset.UTC));
      JSONObject payload = signSns(new JSONObject(resource("/webhooks/ses-bounce.json")));
      assertThat(request("/ses/ses", "POST", payload.toString()).statusCode()).isEqualTo(200);
      assertThat(request("/ses/ses", "POST", payload.toString()).body()).contains("duplicate");
      payload.put("MessageId", "subscription").put("Type", "SubscriptionConfirmation").put("Token", "test-token")
         .put("SubscribeURL", "https://sns.us-east-1.amazonaws.com/?Action=ConfirmSubscription&Token=test");
      signSns(payload);
      assertThat(request("/ses/ses", "POST", payload.toString()).statusCode()).isEqualTo(200);
      assertThat(H2Support.all(EmailTables.INBOX).get(1).getValueString("status")).isEqualTo("IGNORED");
      assertThat(fetched).anyMatch(url -> url.contains("ConfirmSubscription"));
      assertThat(request("/ses/ses", "POST", signSns(payload.put("SubscribeURL", "http://evil.example/")).toString()).statusCode()).isEqualTo(401);
      assertThat(request("/ses/ses", "POST", signSns(payload.put("TopicArn", TOPIC + "other")).toString()).statusCode()).isEqualTo(401);
   }

   /*******************************************************************************
    ** Signed raw SendGrid body is accepted and tampering leaves no extra receipt.
    *******************************************************************************/
   @Test
   void sendgridSignedBatch() throws Exception
   {
      provider("SENDGRID");
      var keys = KeyPairGenerator.getInstance("EC").generateKeyPair();
      EmailRuntime.config().withSecretResolver(name -> Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
      String body = resource("/webhooks/sendgrid-batch.json");
      String timestamp = Long.toString(NOW.getEpochSecond());
      Signature signer = Signature.getInstance("SHA256withECDSA");
      signer.initSign(keys.getPrivate());
      signer.update((timestamp + body).getBytes(StandardCharsets.UTF_8));
      String signature = Base64.getEncoder().encodeToString(signer.sign());
      String[] headers = {"X-Twilio-Email-Event-Webhook-Signature", signature, "X-Twilio-Email-Event-Webhook-Timestamp", timestamp};
      assertThat(request("/sendgrid/sendgrid", "POST", body, headers).statusCode()).isEqualTo(200);
      assertThat(request("/sendgrid/sendgrid", "POST", body + " ", headers).statusCode()).isEqualTo(401);
      assertThat(request("/sendgrid/sendgrid", "POST", body, headers).body()).contains("duplicate");
   }

   /*******************************************************************************
    ** Mailgun replay is 401, but a new signed nonce with an old event id is 200.
    *******************************************************************************/
   @Test
   void mailgunReplayAndDuplicateEvent() throws Exception
   {
      provider("MAILGUN");
      JSONObject payload = signMailgun(new JSONObject(resource("/webhooks/mailgun-failed.json")), "nonce-one", NOW);
      assertThat(request("/mailgun/mailgun", "POST", payload.toString()).body()).contains("received");
      assertThat(request("/mailgun/mailgun", "POST", payload.toString()).statusCode()).isEqualTo(401);
      signMailgun(payload, "nonce-two", NOW);
      assertThat(request("/mailgun/mailgun", "POST", payload.toString()).body()).contains("duplicate");
      signMailgun(payload, "stale", NOW.minusSeconds(301));
      assertThat(request("/mailgun/mailgun", "POST", payload.toString()).statusCode()).isEqualTo(401);
   }

   /*******************************************************************************
    ** Concurrent receipt dedup is durable across separate HTTP worker threads.
    *******************************************************************************/
   @Test
   void concurrentDuplicates() throws Exception
   {
      provider("POSTMARK");
      String body = resource("/webhooks/postmark-bounce.json");
      try(var workers = Executors.newFixedThreadPool(2))
      {
         var first = workers.submit(() -> request("/postmark/postmark", "POST", body, "Authorization", auth()));
         var second = workers.submit(() -> request("/postmark/postmark", "POST", body, "Authorization", auth()));
         assertThat(first.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
         assertThat(second.get(20, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
         assertThat(H2Support.all(EmailTables.INBOX)).hasSize(1);
      }
   }

   /*******************************************************************************
    ** GET never opts out, page content is escaped and one-click returns no body.
    *******************************************************************************/
   @Test
   void unsubscribeGetAndBothPosts() throws Exception
   {
      QRecord message = message(provider("POSTMARK"));
      QRecord brand = H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "brand").withValue("label", "<script>Brand & Co</script>"));
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", message.getValue("id")).withValue("brandId", brand.getValue("id")));
      String token = token(message);
      var get = request("/unsubscribe/" + token, "GET", null);
      assertThat(get.statusCode()).isEqualTo(200);
      assertThat(get.body()).contains("&lt;script&gt;Brand &amp; Co&lt;/script&gt;", "Unsubscribe from Marketing", "color-scheme:light dark", "prefers-color-scheme:dark", "<form method=\"post\">")
         .doesNotContain("<script>", "recipient@example.com", "http://", "https://");
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).isEmpty();
      assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
      assertThat(request("/unsubscribe/" + token, "POST", "anything").statusCode()).isEqualTo(400);
      var click = request("/unsubscribe/" + token, "POST", "List-Unsubscribe=One-Click", "Content-Type", "application/x-www-form-urlencoded");
      assertThat(click.statusCode()).isEqualTo(200);
      assertThat(click.body()).isEmpty();
      assertThat(request("/unsubscribe/" + token, "POST", "confirm=unsubscribe").body()).contains("You are unsubscribed").doesNotContain("<form");
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(1);
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1);
      assertThat(H2Support.all(EmailTables.SUPPRESSION).getFirst().getValueString("scope")).isEqualTo("MARKETING");
      assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueString("status")).isEqualTo("SENT");
      assertThat(request("/unsubscribe/" + token, "POST", "x".repeat(1024 * 1024 + 1)).statusCode()).isEqualTo(413);
   }

   /*******************************************************************************
    ** Real enqueue uses the existing signer and preserves the ad-hoc brand label.
    *******************************************************************************/
   @Test
   void queuedAdHocBrandAndSigner() throws Exception
   {
      provider("POSTMARK");
      QRecord brand = H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "chosen-brand").withValue("label", "Chosen Brand"));
      var result = com.kingsrook.qbits.email.outbox.EmailService.send(new com.kingsrook.qbits.email.outbox.EmailRequest()
         .withTo("recipient@example.com").withSubject("Notice").withMarkdownBody("A notice").withBrandName("chosen-brand")
         .withCategory(com.kingsrook.qbits.email.outbox.EmailCategory.NOTIFICATION));
      assertThat(result.status()).isEqualTo(com.kingsrook.qbits.email.outbox.EmailSendResult.Status.QUEUED);
      QRecord stored = H2Support.all(EmailTables.MESSAGE).getFirst();
      assertThat(stored.getValueInteger("brandId")).isEqualTo(brand.getValueInteger("id"));
      String url = stored.getValueString("unsubscribeUrl");
      String path = "/unsubscribe/" + url.substring(url.lastIndexOf('/') + 1);
      assertThat(request(path, "GET", null).body()).contains("Notification email from Chosen Brand?");
      assertThat(request(path, "POST", "List-Unsubscribe=One-Click").statusCode()).isEqualTo(200);
      assertThat(H2Support.all(EmailTables.SUPPRESSION).getFirst().getValueString("scope")).isEqualTo("NOTIFICATION");
   }

   /*******************************************************************************
    ** One signed mailbox is suppressed, while repeat POST restores its opt-out.
    *******************************************************************************/
   @Test
   void unsubscribeSingleRecipientAndRepeatedSuppression() throws Exception
   {
      QRecord provider = provider("POSTMARK");
      QRecord message = message(provider);
      message.withValue("toAddresses", "\"Recipient, One\" <Recipient@example.com>").withValue("category", "NOTIFICATION");
      SystemWrites.updateOne(EmailTables.MESSAGE, message);
      QRecord other = message(provider);
      SystemWrites.updateOne(EmailTables.MESSAGE, other.withValue("toAddresses", "other@example.com"));
      String signed = token(message);
      assertThat(EmailUnsubscribeLinks.verify(signed).getString("addressHash"))
         .isEqualTo(WebhookVerification.hash("recipient@example.com".getBytes(StandardCharsets.UTF_8)));
      String path = "/unsubscribe/" + signed;
      assertThat(request(path, "POST", "confirm=unsubscribe").statusCode()).isEqualTo(200);
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1).allSatisfy(row ->
      {
         assertThat(row.getValueString("scope")).isEqualTo("NOTIFICATION");
         assertThat(row.getValueString("address")).isEqualTo("recipient@example.com");
      });
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(1);
      SystemWrites.delete(EmailTables.SUPPRESSION, new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter());
      assertThat(request(path, "POST", "List-Unsubscribe=One-Click").statusCode()).isEqualTo(200);
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1);
      assertThat(H2Support.all(EmailTables.EVENT)).hasSize(1);
   }

   /*******************************************************************************
    ** Legacy single-recipient links keep working; legacy multi-recipient links fail closed.
    *******************************************************************************/
   @Test
   void legacyUnsubscribeRequiresSingleRecipient() throws Exception
   {
      QRecord message = message(provider("POSTMARK"));
      String safe = legacyToken(message);
      assertThat(request("/unsubscribe/" + safe, "GET", null).statusCode()).isEqualTo(200);
      for(String field : List.of("toAddresses", "ccAddresses", "bccAddresses"))
      {
         QRecord changed = new QRecord().withValue("id", message.getValue("id")).withValue("toAddresses", "recipient@example.com")
            .withValue("ccAddresses", null).withValue("bccAddresses", null)
            .withValue(field, "toAddresses".equals(field) ? "recipient@example.com, other@example.com" : "other@example.com");
         SystemWrites.updateOne(EmailTables.MESSAGE, changed);
         changed.withValue("category", "MARKETING");
         String legacy = legacyToken(changed);
         for(String signed : List.of(safe, legacy))
         {
            assertThat(request("/unsubscribe/" + signed, "GET", null).statusCode()).isEqualTo(404);
            assertThat(request("/unsubscribe/" + signed, "POST", "List-Unsubscribe=One-Click").statusCode()).isEqualTo(404);
         }
      }
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).isEmpty();
      assertThat(H2Support.all(EmailTables.EVENT)).isEmpty();
      SystemWrites.updateOne(EmailTables.MESSAGE, message.withValue("ccAddresses", null).withValue("bccAddresses", null));
      assertThat(request("/unsubscribe/" + safe, "POST", "List-Unsubscribe=One-Click").statusCode()).isEqualTo(200);
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).hasSize(1);
   }

   /*******************************************************************************
    ** Reproduce the old signature without relying on the current signer's validation.
    *******************************************************************************/
   private static String legacyToken(QRecord message) throws Exception
   {
      String envelope = message.getValueString("toAddresses") + "," + message.getValueString("ccAddresses") + "," + message.getValueString("bccAddresses");
      JSONObject payload = new JSONObject().put("messageId", message.getValueString("id")).put("category", message.getValueString("category"))
         .put("addressHash", WebhookVerification.hash(envelope.getBytes(StandardCharsets.UTF_8)));
      String signed = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().getBytes(StandardCharsets.UTF_8));
      javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
      mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return (signed + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8))));
   }

   /*******************************************************************************
    ** Signer and receiver reject non-opt-out categories, including valid HMACs.
    *******************************************************************************/
   @Test
   void invalidAndTransactionalTokens() throws Exception
   {
      QRecord message = message(provider("POSTMARK"));
      String token = token(message);
      assertThat(request("/unsubscribe/bad-token", "GET", null).statusCode()).isEqualTo(404);
      assertThat(request("/unsubscribe/" + token + "x", "POST", "List-Unsubscribe=One-Click").statusCode()).isEqualTo(404);
      for(String category : List.of("TRANSACTIONAL", "SECURITY"))
      {
         assertThat(EmailUnsubscribeLinks.create("1", "recipient@example.com", category)).isNull();
         JSONObject data = EmailUnsubscribeLinks.verify(token).put("category", category);
         String signed = "v1." + Base64.getUrlEncoder().withoutPadding().encodeToString(data.toString().getBytes(StandardCharsets.UTF_8));
         javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
         mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
         String forged = signed + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
         assertThat(request("/unsubscribe/" + forged, "POST", "List-Unsubscribe=One-Click").statusCode()).isEqualTo(404);
      }
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", message.getValue("id")).withValue("toAddresses", "changed@example.com"));
      assertThat(request("/unsubscribe/" + token, "GET", null).statusCode()).isEqualTo(404);
      assertThat(H2Support.all(EmailTables.SUPPRESSION)).isEmpty();
   }

   /*******************************************************************************
    ** Logs at normal operational levels never contain addresses or payload bodies.
    *******************************************************************************/
   @Test
   void logsContainNoAddresses() throws Exception
   {
      QRecord provider = provider("POSTMARK");
      message(provider);
      List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
      var capture = new org.apache.logging.log4j.core.appender.AbstractAppender("tracking-privacy", null, null, true, null)
      {
         @Override
         public void append(org.apache.logging.log4j.core.LogEvent event)
         {
            // Match log4j2-test.xml: core's explicitly OFF diagnostics are not output.
            if(event.getLevel() == org.apache.logging.log4j.Level.OFF)
            {
               return;
            }
            logs.add(event.getMessage().getFormattedMessage());
            if(event.getThrown() != null)
            {
               logs.add(event.getThrown().toString());
            }
         }
      };
      capture.start();
      var context = (org.apache.logging.log4j.core.LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
      var root = context.getConfiguration().getRootLogger();
      var previousLevel = root.getLevel();
      root.addAppender(capture, org.apache.logging.log4j.Level.ALL, null);
      root.setLevel(org.apache.logging.log4j.Level.INFO);
      context.updateLoggers();
      try
      {
         String body = resource("/webhooks/postmark-bounce.json");
         assertThat(request("/postmark/postmark", "POST", body, "Authorization", auth()).statusCode()).isEqualTo(200);
         assertThat(request("/postmark/postmark", "POST", body, "Authorization", auth()).statusCode()).isEqualTo(200);
         assertThat(request("/postmark/postmark", "POST", body).statusCode()).isEqualTo(401);
         new EmailInboxProcessor().runOnce();
         String broken = new JSONObject(body).put("ID", 999).put("MessageID", "missing").toString();
         assertThat(request("/postmark/postmark", "POST", broken, "Authorization", auth()).statusCode()).isEqualTo(200);
         new EmailInboxProcessor().runOnce();
         assertThat(String.join("\n", logs)).doesNotContain("recipient@example.com", "sender@example.com", SECRET, "RecordType", "550 mailbox");
         assertThat(H2Support.all(EmailTables.INBOX).getLast().getValueString("lastError")).doesNotContain("@", "missing");
      }
      finally
      {
         root.removeAppender(capture.getName());
         root.setLevel(previousLevel);
         context.updateLoggers();
         capture.stop();
      }
   }

   /*******************************************************************************
    ** Path validation excludes UI API routes and route metacharacters.
    *******************************************************************************/
   @Test
   void routeConfigurationValidation()
   {
      for(String path : List.of("relative", "/qqq/v1", "/qqq/v1/email", "/hooks/{param}", "//hooks", "/hooks/"))
      {
         List<String> errors = new ArrayList<>();
         EmailRuntime.config().withWebhookBasePath(path).validate(instance, errors);
         assertThat(errors).anyMatch(error -> error.contains("webhookBasePath"));
      }
      List<String> errors = new ArrayList<>();
      EmailRuntime.config().withWebhookBasePath("/qqq/hooks/email").validate(instance, errors);
      assertThat(errors).isEmpty();
   }

   /*******************************************************************************
    ** Both system tables are readable by viewers and reject direct user writes.
    *******************************************************************************/
   @Test
   void systemTablePermissions() throws Exception
   {
      QContext.getQSession().setPermissions(java.util.Set.of("email.view"));
      var checker = new com.kingsrook.qbits.email.permissions.EmailPermissionChecker();
      for(String table : List.of(EmailTables.EVENT, EmailTables.INBOX))
      {
         var metadata = instance.getTable(table);
         checker.checkPermissionsThrowing(new com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput(table), metadata);
         assertThatThrownBy(() -> checker.checkPermissionsThrowing(new com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput(table), metadata))
            .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
         assertThatThrownBy(() -> checker.checkPermissionsThrowing(new com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput(table), metadata))
            .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
         assertThatThrownBy(() -> checker.checkPermissionsThrowing(new com.kingsrook.qqq.backend.core.model.actions.tables.delete.DeleteInput(table), metadata))
            .isInstanceOf(com.kingsrook.qqq.backend.core.exceptions.QPermissionDeniedException.class);
      }
      assertThat(instance.getTable(EmailTables.PROVIDER).getSections()).anyMatch(section -> (EmailTables.INBOX + "_children").equals(section.getWidgetName()));
      assertThat(instance.getTable(EmailTables.MESSAGE).getSections()).anyMatch(section -> (EmailTables.EVENT + "_children").equals(section.getWidgetName()));
   }

   /*******************************************************************************
    ** Sign the same single To mailbox as enqueue.
    *******************************************************************************/
   private static String token(QRecord message)
   {
      String url = EmailUnsubscribeLinks.create(message.getValueString("id"), message.getValueString("toAddresses"), message.getValueString("category"));
      assertThat(url).startsWith("https://app.example.com" + BASE + "/unsubscribe/");
      return (url.substring(url.lastIndexOf('/') + 1));
   }
}
