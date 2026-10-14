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

package com.kingsrook.qbits.email.transport;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.delivery.EmailDispatcher;
import com.kingsrook.qbits.email.metadata.EmailTableCustomizers;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailSendResult;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/*******************************************************************************
 ** Provider contracts, safe failures and database-to-HTTP delivery without vendors.
 *******************************************************************************/
class EmailHttpTransportsTest
{
   private static final String SECRET = "fake-sensitive-token";
   private static final String ECHO = "private-recipient@example.com " + SECRET;



   /*******************************************************************************
    ** A deterministic response without any network or third-party test dependency.
    *******************************************************************************/
   private record Response(int statusCode, HttpHeaders headers, String body) implements HttpResponse<String>
   {
      /*******************************************************************************
       ** Unused request metadata.
       *******************************************************************************/
      @Override
      public HttpRequest request()
      {
         return (null);
      }

      /*******************************************************************************
       ** Redirects are never followed.
       *******************************************************************************/
      @Override
      public Optional<HttpResponse<String>> previousResponse()
      {
         return (Optional.empty());
      }

      /*******************************************************************************
       ** No TLS session is needed for the fake.
       *******************************************************************************/
      @Override
      public Optional<SSLSession> sslSession()
      {
         return (Optional.empty());
      }

      /*******************************************************************************
       ** No response URL is consumed by a transport.
       *******************************************************************************/
      @Override
      public URI uri()
      {
         return (URI.create("https://example.com"));
      }

      /*******************************************************************************
       ** Test protocol version.
       *******************************************************************************/
      @Override
      public HttpClient.Version version()
      {
         return (HttpClient.Version.HTTP_1_1);
      }
   }



   /*******************************************************************************
    ** Build an HTTP response with case-insensitive headers.
    *******************************************************************************/
   private static Response response(int status, String body, Map<String, List<String>> headers)
   {
      return (new Response(status, HttpHeaders.of(headers, (name, value) -> true), body));
   }



   /*******************************************************************************
    ** The same envelope exercises alternatives, display names and blind recipients.
    *******************************************************************************/
   private static EmailEnvelope envelope()
   {
      return (new EmailEnvelope().withMessageId("42").withFrom("Sender <sender@example.com>")
         .withTo(List.of("Person <to@example.com>")).withCc(List.of("cc@example.com")).withBcc(List.of("bcc@example.com"))
         .withReplyTo("Replies <reply@example.com>").withSubject("Hello \u00e9").withTextBody("Plain \u00e9").withHtmlBody("<b>Hello \u00e9</b>")
         .withHeaders(Map.of("List-Unsubscribe", "<https://example.com/unsubscribe>",
            "List-Unsubscribe-Post", "List-Unsubscribe=One-Click", "X-Custom", "kept")));
   }



   /*******************************************************************************
    ** Required row fields, using the new default allowlisted credential names.
    *******************************************************************************/
   private static QRecord row(String kind)
   {
      String env = switch(kind)
      {
         case "POSTMARK" -> "POSTMARK_SERVER_TOKEN";
         case "SENDGRID" -> "SENDGRID_API_KEY";
         case "MAILGUN" -> "MAILGUN_API_KEY";
         default -> "GMAIL_APP_PASSWORD";
      };
      return (new QRecord().withValue("kind", kind).withValue("secretEnvName", env)
         .withValue("fromAddress", "sender@example.com").withValue("sendingDomain", "example.com").withValue("region", "US"));
   }



   /*******************************************************************************
    ** Secrets are supplied only when the transport starts its attempt.
    *******************************************************************************/
   private static EmailProviderSettings settings(QRecord row)
   {
      return (new EmailProviderSettings(row, new EmailQBitConfig().withSecretResolver(name -> SECRET)));
   }



   /*******************************************************************************
    ** Select the real transport behind the fake exchange seam.
    *******************************************************************************/
   private static EmailTransport transport(String kind, EmailHttp http)
   {
      return (switch(kind)
      {
         case "POSTMARK" -> new PostmarkEmailTransport(http);
         case "SENDGRID" -> new SendGridEmailTransport(http);
         case "MAILGUN" -> new MailgunEmailTransport(http);
         default -> throw new IllegalArgumentException(kind);
      });
   }



   /*******************************************************************************
    ** Read an in-memory request publisher just as a client would.
    *******************************************************************************/
   private static byte[] body(HttpRequest request) throws Exception
   {
      var subscriber = HttpResponse.BodySubscribers.ofByteArray();
      request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>()
      {
         /*******************************************************************************
          ** Pass demand through to the in-memory publisher.
          *******************************************************************************/
         @Override
         public void onSubscribe(java.util.concurrent.Flow.Subscription subscription)
         {
            subscriber.onSubscribe(subscription);
         }

         /*******************************************************************************
          ** Request publishers emit buffers; response subscribers consume buffer lists.
          *******************************************************************************/
         @Override
         public void onNext(java.nio.ByteBuffer buffer)
         {
            subscriber.onNext(List.of(buffer));
         }

         /*******************************************************************************
          ** Preserve test failures through the completion stage.
          *******************************************************************************/
         @Override
         public void onError(Throwable error)
         {
            subscriber.onError(error);
         }

         /*******************************************************************************
          ** Complete the byte array.
          *******************************************************************************/
         @Override
         public void onComplete()
         {
            subscriber.onComplete();
         }
      });
      return (subscriber.getBody().toCompletableFuture().get());
   }



   /*******************************************************************************
    ** Postmark includes all rendered fields, opt-in tracking and the chosen stream.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void postmarkRequestAndSuccess(boolean tracking) throws Exception
   {
      AtomicReference<HttpRequest> captured = new AtomicReference<>();
      var transport = new PostmarkEmailTransport(request ->
      {
         captured.set(request);
         return (response(200, "{\"ErrorCode\":0,\"MessageID\":\"pm-id\",\"Message\":\"" + ECHO + "\"}", Map.of()));
      });
      var provider = row("POSTMARK").withValue("trackOpens", tracking).withValue("trackClicks", tracking)
         .withValue("messageStream", tracking ? "broadcasts" : null);
      assertThat(transport.send(envelope(), settings(provider)).providerMessageId()).isEqualTo("pm-id");
      HttpRequest request = captured.get();
      assertThat(request.uri().toString()).isEqualTo("https://api.postmarkapp.com/email");
      assertThat(request.method()).isEqualTo("POST");
      assertThat(request.timeout()).contains(Duration.ofSeconds(30));
      assertThat(request.headers().firstValue("X-Postmark-Server-Token")).contains(SECRET);
      assertThat(request.headers().firstValue("Content-Type")).contains("application/json");
      JsonNode json = EmailHttpSupport.JSON.readTree(body(request));
      assertThat(json.path("From").asText()).isEqualTo(envelope().getFrom());
      assertThat(json.path("To").asText()).isEqualTo("Person <to@example.com>");
      assertThat(json.path("Cc").asText()).isEqualTo("cc@example.com");
      assertThat(json.path("Bcc").asText()).isEqualTo("bcc@example.com");
      assertThat(json.path("ReplyTo").asText()).isEqualTo(envelope().getReplyTo());
      assertThat(json.path("Subject").asText()).isEqualTo(envelope().getSubject());
      assertThat(json.path("TextBody").asText()).isEqualTo(envelope().getTextBody());
      assertThat(json.path("HtmlBody").asText()).isEqualTo(envelope().getHtmlBody());
      assertThat(json.path("MessageStream").asText()).isEqualTo(tracking ? "broadcasts" : "outbound");
      assertThat(json.path("TrackOpens").asBoolean()).isEqualTo(tracking);
      assertThat(json.path("TrackLinks").asText()).isEqualTo(tracking ? "HtmlAndText" : "None");
      assertThat(json.path("Headers").toString()).contains("List-Unsubscribe", "List-Unsubscribe-Post", "X-QQQ-Email-Id", "42", "Auto-Submitted");
      assertThat(json.toString()).doesNotContain(SECRET);
   }



   /*******************************************************************************
    ** SendGrid filters forbidden headers case-insensitively, retaining unsubscribe.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void sendGridRequestAndSuccess(boolean tracking) throws Exception
   {
      AtomicReference<HttpRequest> captured = new AtomicReference<>();
      var transport = new SendGridEmailTransport(request ->
      {
         captured.set(request);
         return (response(202, "", Map.of("x-message-id", List.of("sg-id"))));
      });
      Map<String, String> headers = new java.util.HashMap<>(envelope().getHeaders());
      for(String reserved : List.of("X-SG-ID", "x-sg-eid", "Received", "DKIM-Signature", "Content-Type",
         "Content-Transfer-Encoding", "To", "From", "Subject", "Reply-To", "CC", "BcC"))
      {
         headers.put(reserved, "must-be-removed");
      }
      assertThat(transport.send(envelope().withHeaders(headers), settings(row("SENDGRID")
         .withValue("trackOpens", tracking).withValue("trackClicks", tracking))).providerMessageId()).isEqualTo("sg-id");
      HttpRequest request = captured.get();
      assertThat(request.uri().toString()).isEqualTo("https://api.sendgrid.com/v3/mail/send");
      assertThat(request.method()).isEqualTo("POST");
      assertThat(request.timeout()).contains(Duration.ofSeconds(30));
      assertThat(request.headers().firstValue("Authorization")).contains("Bearer " + SECRET);
      JsonNode json = EmailHttpSupport.JSON.readTree(body(request));
      assertThat(json.at("/personalizations/0/to/0/email").asText()).isEqualTo("to@example.com");
      assertThat(json.at("/personalizations/0/to/0/name").asText()).isEqualTo("Person");
      assertThat(json.at("/personalizations/0/cc/0/email").asText()).isEqualTo("cc@example.com");
      assertThat(json.at("/personalizations/0/bcc/0/email").asText()).isEqualTo("bcc@example.com");
      assertThat(json.at("/from/email").asText()).isEqualTo("sender@example.com");
      assertThat(json.at("/from/name").asText()).isEqualTo("Sender");
      assertThat(json.at("/reply_to/email").asText()).isEqualTo("reply@example.com");
      assertThat(json.path("subject").asText()).isEqualTo(envelope().getSubject());
      assertThat(json.at("/content/0/type").asText()).isEqualTo("text/plain");
      assertThat(json.at("/content/0/value").asText()).isEqualTo(envelope().getTextBody());
      assertThat(json.at("/content/1/type").asText()).isEqualTo("text/html");
      assertThat(json.at("/content/1/value").asText()).isEqualTo(envelope().getHtmlBody());
      assertThat(json.at("/custom_args/qqq_email_id").asText()).isEqualTo("42");
      assertThat(json.path("headers").toString()).doesNotContain("must-be-removed").contains("List-Unsubscribe", "List-Unsubscribe-Post");
      assertThat(json.at("/tracking_settings/open_tracking/enable").asBoolean()).isEqualTo(tracking);
      assertThat(json.at("/tracking_settings/click_tracking/enable").asBoolean()).isEqualTo(tracking);
      assertThat(json.at("/tracking_settings/click_tracking/enable_text").asBoolean()).isEqualTo(tracking);
      assertThat(json.toString()).doesNotContain(SECRET);
   }



   /*******************************************************************************
    ** Multipart preserves the MIME bytes and explicit Bcc envelope destinations.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({"US,false", "EU,true"})
   void mailgunRequestAndSuccess(String region, boolean tracking) throws Exception
   {
      AtomicReference<HttpRequest> captured = new AtomicReference<>();
      var transport = new MailgunEmailTransport(request ->
      {
         captured.set(request);
         return (response(200, "{\"id\":\"<mg-id@example.com>\",\"message\":\"" + ECHO + "\"}", Map.of()));
      });
      assertThat(transport.send(envelope(), settings(row("MAILGUN").withValue("region", region)
         .withValue("trackOpens", tracking).withValue("trackClicks", tracking))).providerMessageId()).isEqualTo("mg-id@example.com");
      HttpRequest request = captured.get();
      assertThat(request.uri().toString()).isEqualTo("https://api." + ("EU".equals(region) ? "eu." : "") + "mailgun.net/v3/example.com/messages.mime");
      assertThat(request.method()).isEqualTo("POST");
      assertThat(request.timeout()).contains(Duration.ofSeconds(30));
      assertThat(request.headers().firstValue("Authorization")).contains("Basic " + Base64.getEncoder().encodeToString(("api:" + SECRET).getBytes(StandardCharsets.UTF_8)));
      String contentType = request.headers().firstValue("Content-Type").orElseThrow();
      String boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
      String multipart = new String(body(request), StandardCharsets.UTF_8);
      assertThat(multipart).startsWith("--" + boundary + "\r\n").endsWith("\r\n--" + boundary + "--\r\n");
      assertThat(multipart).contains("name=\"to\"\r\n\r\nto@example.com,cc@example.com,bcc@example.com\r\n");
      assertThat(multipart).contains("name=\"o:tracking-opens\"\r\n\r\n" + (tracking ? "yes" : "no") + "\r\n");
      assertThat(multipart).contains("name=\"o:tracking-clicks\"\r\n\r\n" + (tracking ? "yes" : "no") + "\r\n");
      assertThat(multipart).contains("name=\"v:qqq_email_id\"\r\n\r\n42\r\n", "name=\"message\"; filename=\"message.eml\"");
      String mime = multipart.substring(multipart.indexOf("Content-Type: message/rfc822\r\n\r\n") + "Content-Type: message/rfc822\r\n\r\n".length());
      mime = mime.substring(0, mime.lastIndexOf("\r\n--" + boundary));
      assertThat(mime).doesNotContain("bcc@example.com", "Bcc:", SECRET);
      MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(mime.getBytes(StandardCharsets.UTF_8)));
      assertThat(message.getSubject()).isEqualTo(envelope().getSubject());
      assertThat(message.getHeader("X-QQQ-Email-Id", null)).isEqualTo("42");
      assertThat(message.getHeader("List-Unsubscribe-Post", null)).isEqualTo("List-Unsubscribe=One-Click");
      assertThat(message.getRecipients(jakarta.mail.Message.RecipientType.CC)[0].toString()).isEqualTo("cc@example.com");
      var alternatives = (jakarta.mail.Multipart) message.getContent();
      assertThat(alternatives.getBodyPart(0).getContent().toString()).isEqualTo(envelope().getTextBody());
      assertThat(alternatives.getBodyPart(1).getContent().toString()).isEqualTo(envelope().getHtmlBody());
   }



   /*******************************************************************************
    ** Provider codes are numeric and never include echoed diagnostics.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({
      "10,false,true", "300,true,false", "406,true,false", "400,false,true", "401,false,true",
      "405,false,true", "412,false,true", "413,false,true", "422,false,true",
      "1235,false,true", "1236,false,true", "1480,false,true", "403,true,false"
   })
   void postmarkErrorCodes(int code, boolean permanent, boolean providerLevel)
   {
      assertFailure(new PostmarkEmailTransport(request -> response(422,
         "{\"ErrorCode\":" + code + ",\"Message\":\"" + ECHO + "\"}", Map.of())), "POSTMARK", permanent, providerLevel, null, "code " + code);
   }



   /*******************************************************************************
    ** HTTP status classification takes precedence over Postmark's JSON error code.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({
      "POSTMARK,401,false,true", "POSTMARK,403,false,true", "POSTMARK,400,true,false", "POSTMARK,413,true,false",
      "POSTMARK,429,false,false", "POSTMARK,500,false,false", "POSTMARK,503,false,false", "POSTMARK,302,false,true",
      "SENDGRID,401,false,true", "SENDGRID,403,false,true", "SENDGRID,400,true,false", "SENDGRID,413,true,false",
      "SENDGRID,429,false,false", "SENDGRID,500,false,false", "SENDGRID,503,false,false", "SENDGRID,302,false,true",
      "MAILGUN,401,false,true", "MAILGUN,403,false,true", "MAILGUN,400,true,false", "MAILGUN,413,true,false",
      "MAILGUN,429,false,false", "MAILGUN,500,false,false", "MAILGUN,503,false,false", "MAILGUN,302,false,true"
   })
   void httpFailures(String kind, int status, boolean permanent, boolean providerLevel)
   {
      String body = status >= 429 ? "{\"ErrorCode\":10,\"Message\":\"" + ECHO + "\"}" : ECHO;
      assertFailure(transport(kind, request -> response(status, body, Map.of("Retry-After", List.of("123")))),
         kind, permanent, providerLevel, Duration.ofSeconds(123), "HTTP " + status);
   }



   /*******************************************************************************
    ** Assert flags and the entire exposed exception remain free of diagnostics.
    *******************************************************************************/
   private static void assertFailure(EmailTransport transport, String kind, boolean permanent, boolean providerLevel, Duration retryAfter, String message)
   {
      assertThatThrownBy(() -> transport.send(envelope(), settings(row(kind))))
         .isInstanceOfSatisfying(EmailTransportException.class, error ->
         {
            assertThat(error.getPermanent()).isEqualTo(permanent);
            assertThat(error.getProviderLevel()).isEqualTo(providerLevel);
            assertThat(error.getRetryAfter()).isEqualTo(retryAfter);
            assertThat(error).hasMessageContaining(message).hasNoCause();
            assertThat(error.toString()).doesNotContain(SECRET, ECHO, "private-recipient");
         });
   }



   /*******************************************************************************
    ** Support delta seconds, HTTP dates, stale dates and invalid retry headers.
    *******************************************************************************/
   @Test
   void retryAfterAndHttpPolicy() throws Exception
   {
      String future = ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(5).format(DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration delay = EmailHttpSupport.retryAfter(response(429, "", Map.of("retry-after", List.of(future))));
      assertThat(delay.getSeconds()).isBetween(295L, 300L);
      assertThat(EmailHttpSupport.retryAfter(response(429, "", Map.of("retry-after", List.of("0"))))).isEqualTo(Duration.ZERO);
      assertThat(EmailHttpSupport.retryAfter(response(429, "", Map.of("retry-after", List.of("Sun, 06 Nov 1994 08:49:37 GMT"))))).isEqualTo(Duration.ZERO);
      for(String value : List.of("-1", "99999999999999999999999", "invalid", ""))
      {
         assertThat(EmailHttpSupport.retryAfter(response(429, "", Map.of("retry-after", List.of(value))))).isNull();
      }
      try(HttpClient client = EmailHttp.newClient())
      {
         assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
         assertThat(client.connectTimeout()).contains(Duration.ofSeconds(10));
      }
      assertThatThrownBy(() -> EmailHttp.sendJdk(HttpRequest.newBuilder(URI.create("http://127.0.0.1/")).build()))
         .isInstanceOf(IOException.class).hasMessage("Email HTTP requires HTTPS");
   }



   /*******************************************************************************
    ** Unknown or malformed successes must not be silently recorded as sent.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(strings = {"POSTMARK", "SENDGRID", "MAILGUN"})
   void invalidResponsesAndNetworkFailures(String kind) throws Exception
   {
      int success = "SENDGRID".equals(kind) ? 202 : 200;
      for(String body : List.of(ECHO, "", "[]", "null", "{}", "{\"ErrorCode\":0}", "{\"id\":123}", "{\"ErrorCode\":\"" + SECRET + "\"}"))
      {
         assertFailure(transport(kind, request -> response(success, body, Map.of())), kind, false, false, null, "provider");
      }
      assertFailure(transport(kind, request ->
      {
         throw new IOException(ECHO);
      }), kind, false, false, null, "temporarily unavailable");
      assertFailure(transport(kind, request ->
      {
         throw new IllegalArgumentException(ECHO);
      }), kind, false, true, null, "configuration invalid");
      try
      {
         assertFailure(transport(kind, request ->
         {
            throw new InterruptedException(ECHO);
         }), kind, false, false, null, "interrupted");
         assertThat(Thread.currentThread().isInterrupted()).isTrue();
      }
      finally
      {
         Thread.interrupted();
      }
   }



   /*******************************************************************************
    ** Required secrets resolve per attempt, never on construction or failed validation.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(strings = {"POSTMARK", "SENDGRID", "MAILGUN"})
   void secretResolutionAndInvalidEnvelopes(String kind) throws Exception
   {
      EmailHttp mustNotSend = request ->
      {
         throw new AssertionError("No HTTP request should have been sent");
      };
      var transport = transport(kind, mustNotSend);
      var config = new EmailQBitConfig().withSecretResolver(name -> null);
      assertThatThrownBy(() -> transport.send(envelope(), new EmailProviderSettings(row(kind), config)))
         .hasNoCause().hasMessage("Credential environment variable is not set");
      config.withSecretResolver(name ->
      {
         throw new IllegalStateException(ECHO);
      });
      assertThatThrownBy(() -> transport.send(envelope(), new EmailProviderSettings(row(kind), config)))
         .hasNoCause().hasMessage("Credential environment variable is not set");
      assertThatThrownBy(() -> transport.send(envelope(), settings(row(kind).withValue("secretEnvName", null))))
         .hasNoCause().hasMessage("Provider needs a secret environment name");
      assertThatThrownBy(() -> transport.send(envelope(), settings(row(kind).withValue("secretEnvName", "UNAPPROVED"))))
         .hasNoCause().hasMessage("Credential environment name is not allowed");
      for(EmailEnvelope invalid : List.of(envelope().withSubject("Injected\r\nBcc: " + ECHO),
         envelope().withTo(List.of("bad\r\naddress")), envelope().withHeaders(Map.of("X-Test", "bad\n" + ECHO))))
      {
         assertThatThrownBy(() -> transport.send(invalid, settings(row(kind))))
            .hasNoCause().hasMessage("Invalid email message");
      }

      AtomicInteger resolutions = new AtomicInteger();
      AtomicInteger sends = new AtomicInteger();
      var rotating = new EmailProviderSettings(row(kind), new EmailQBitConfig().withSecretResolver(name -> "token-" + resolutions.incrementAndGet()));
      var real = transport(kind, request ->
      {
         String token = "token-" + sends.incrementAndGet();
         String auth = "POSTMARK".equals(kind) ? request.headers().firstValue("X-Postmark-Server-Token").orElseThrow()
            : request.headers().firstValue("Authorization").orElseThrow();
         String expected = "MAILGUN".equals(kind) ? "Basic " + Base64.getEncoder().encodeToString(("api:" + token).getBytes(StandardCharsets.UTF_8))
            : "SENDGRID".equals(kind) ? "Bearer " + token : token;
         assertThat(auth).isEqualTo(expected);
         return (response("SENDGRID".equals(kind) ? 202 : 200,
            "{\"ErrorCode\":0,\"MessageID\":\"id\",\"id\":\"id\"}", Map.of("X-Message-Id", List.of("id"))));
      });
      assertThat(resolutions.get()).isZero();
      real.send(envelope(), rotating);
      real.send(envelope(), rotating);
      assertThat(resolutions.get()).isEqualTo(2);
   }



   /*******************************************************************************
    ** Optional fields are omitted, null tracking is disabled and streams default.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(strings = {"POSTMARK", "SENDGRID"})
   void minimalJsonMessages(String kind) throws Exception
   {
      AtomicReference<HttpRequest> captured = new AtomicReference<>();
      var transport = transport(kind, request ->
      {
         captured.set(request);
         return (response("SENDGRID".equals(kind) ? 202 : 200, "{\"ErrorCode\":0,\"MessageID\":\"id\"}",
            Map.of("X-Message-Id", List.of("id"))));
      });
      for(boolean htmlOnly : List.of(true, false))
      {
         var minimal = envelope().withFrom("sender@example.com").withCc(List.of()).withBcc(List.of()).withReplyTo(null)
            .withTextBody(htmlOnly ? null : "plain").withHtmlBody(htmlOnly ? "<b>html</b>" : null);
         transport.send(minimal, settings(row(kind).withValue("messageStream", "")));
         JsonNode json = EmailHttpSupport.JSON.readTree(body(captured.get()));
         if("SENDGRID".equals(kind))
         {
            assertThat(json.at("/content").size()).isEqualTo(1);
            assertThat(json.at("/personalizations/0").has("cc")).isFalse();
            assertThat(json.at("/personalizations/0").has("bcc")).isFalse();
            assertThat(json.has("reply_to")).isFalse();
            assertThat(json.at("/from").has("name")).isFalse();
         }
         else
         {
            assertThat(json.has("ReplyTo")).isFalse();
            assertThat(json.has(htmlOnly ? "TextBody" : "HtmlBody")).isFalse();
            assertThat(json.path("MessageStream").asText()).isEqualTo("outbound");
         }
      }
   }



   /*******************************************************************************
    ** Authentication and regional requirements are validated without resolving values.
    *******************************************************************************/
   @Test
   void providerValidationAndGmailPreset() throws Exception
   {
      var config = new EmailQBitConfig().withSecretResolver(name ->
      {
         throw new AssertionError("Validation must not resolve a secret");
      });
      for(String kind : List.of("POSTMARK", "SENDGRID", "MAILGUN"))
      {
         config.withAllowedEnvNames(Set.of());
         QRecord provider = row(kind);
         assertThat(EmailTableCustomizers.validateProvider(provider, config)).isEmpty();
         assertThat(EmailTableCustomizers.validateProvider(provider.withValue("secretEnvName", null), config))
            .contains("Provider needs a secret environment name");
         provider.withValue("secretEnvName", "CUSTOM_TOKEN");
         assertThat(EmailTableCustomizers.validateProvider(provider, config)).isNotEmpty();
         assertThat(EmailTableCustomizers.validateProvider(provider, config.withAllowedEnvNames(Set.of("CUSTOM_TOKEN")))).isEmpty();
      }
      for(String domain : List.of("", "https://example.com", "../example.com", "example.com/path", "example.com?x=y", "-bad.com", "bad-.com"))
      {
         assertThat(EmailTableCustomizers.validateProvider(row("MAILGUN").withValue("sendingDomain", domain), config)).isNotEmpty();
         assertThatThrownBy(() -> new MailgunEmailTransport(request ->
         {
            throw new AssertionError("Invalid domain must not send");
         }).send(envelope(), settings(row("MAILGUN").withValue("sendingDomain", domain))))
            .isInstanceOfSatisfying(EmailTransportException.class, error -> assertThat(error.getProviderLevel()).isTrue());
      }
      assertThat(EmailTableCustomizers.validateProvider(row("MAILGUN").withValue("region", "EU"), config)).isEmpty();
      assertThat(EmailTableCustomizers.validateProvider(row("MAILGUN").withValue("region", null), config)).contains("Mailgun region must be US or EU");
      assertThat(EmailTableCustomizers.validateProvider(row("MAILGUN").withValue("region", "APAC"), config)).contains("Mailgun region must be US or EU");
      Map<String, String> webhookNames = Map.of("POSTMARK", "POSTMARK_WEBHOOK_PASSWORD", "SENDGRID", "SENDGRID_WEBHOOK_PUBLIC_KEY", "MAILGUN", "MAILGUN_WEBHOOK_SIGNING_KEY");
      webhookNames.forEach((kind, env) -> assertThat(EmailTableCustomizers.validateProvider(row(kind).withValue("webhookSecretEnvName", env), config)).isEmpty());
      assertThat(config.effectiveAllowedEnvNames("SES")).doesNotContain("SES_SNS_TOPIC_ARN");

      QRecord gmail = row("GMAIL_SMTP").withValue("usernameEnvName", "GMAIL_USERNAME");
      assertThat(EmailTableCustomizers.validateProvider(gmail, config)).isEmpty();
      var settings = new EmailProviderSettings(gmail.withValue("host", "ignored.example.com").withValue("port", 25), config);
      assertThat(settings.host()).isEqualTo("smtp.gmail.com");
      assertThat(settings.port()).isEqualTo(587);
      assertThat(settings.security()).isEqualTo("STARTTLS");
      assertThat(new EmailTransportRegistry().get("GMAIL_SMTP")).isInstanceOf(SmtpEmailTransport.class);
      assertThat(EmailTableCustomizers.validateProvider(gmail.withValue("security", "STARTTLS"), config)).isEmpty();
      for(String security : List.of("NONE", "TLS", "invalid"))
      {
         assertThat(EmailTableCustomizers.validateProvider(gmail.withValue("security", security), config.withAllowPlaintextSmtpAuth(true)))
            .contains("Gmail SMTP requires STARTTLS");
         assertThatThrownBy(() -> new SmtpEmailTransport().send(envelope(), settings))
            .isInstanceOfSatisfying(EmailTransportException.class, error -> assertThat(error.getProviderLevel()).isTrue());
      }
      gmail.withValue("security", "STARTTLS").withValue("usernameEnvName", null);
      assertThat(EmailTableCustomizers.validateProvider(gmail, config)).contains("Gmail SMTP needs an account address environment name");
      assertThatThrownBy(() -> new SmtpEmailTransport().send(envelope(), settings)).hasMessageContaining("both credential names");
      gmail.withValue("usernameEnvName", "GMAIL_USERNAME").withValue("secretEnvName", null);
      assertThat(EmailTableCustomizers.validateProvider(gmail, config)).contains("Provider needs a secret environment name");
      assertThat(EmailTableCustomizers.validateProvider(row("GMAIL_API"), config))
         .contains("Gmail API sending is specified but not built; use GMAIL_SMTP");
      assertThatThrownBy(() -> new EmailTransportRegistry().get("GMAIL_API"))
         .hasMessage("Gmail API sending is specified but not built; use GMAIL_SMTP");
      assertThatThrownBy(() -> new EmailTransportRegistry().get("future")).hasMessage("Unknown email provider kind");
      assertThat(new EmailTransportRegistry().get("POSTMARK")).isInstanceOf(PostmarkEmailTransport.class);
      assertThat(new EmailTransportRegistry().get("SENDGRID")).isInstanceOf(SendGridEmailTransport.class);
      assertThat(new EmailTransportRegistry().get("MAILGUN")).isInstanceOf(MailgunEmailTransport.class);
   }



   /*******************************************************************************
    ** A real socket exchange from enqueue through dispatcher, including stored errors.
    *******************************************************************************/
   @ParameterizedTest
   @CsvSource({
      "POSTMARK,200,SENT", "POSTMARK,401,RETRY_WAIT", "POSTMARK,400,DEAD_LETTER", "POSTMARK,429,RETRY_WAIT",
      "SENDGRID,202,SENT", "SENDGRID,403,RETRY_WAIT", "SENDGRID,413,DEAD_LETTER", "SENDGRID,503,RETRY_WAIT",
      "MAILGUN,200,SENT", "MAILGUN,401,RETRY_WAIT", "MAILGUN,400,DEAD_LETTER", "MAILGUN,429,RETRY_WAIT"
   })
   void dispatchThroughLocalHttp(String kind, int status, String expectedStatus) throws Exception
   {
      Instant now = Instant.parse("2026-10-13T12:00:00Z");
      AtomicInteger resolutions = new AtomicInteger();
      AtomicReference<String> received = new AtomicReference<>();
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", exchange ->
      {
         received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
         exchange.getResponseHeaders().set("X-Message-Id", "http-id");
         exchange.getResponseHeaders().set("Retry-After", "123");
         byte[] reply = ("{\"ErrorCode\":" + (status == 200 ? 0 : 300)
            + ",\"MessageID\":\"http-id\",\"id\":\"<http-id>\",\"Message\":\"" + ECHO + "\"}").getBytes(StandardCharsets.UTF_8);
         exchange.sendResponseHeaders(status, reply.length);
         exchange.getResponseBody().write(reply);
         exchange.close();
      });
      server.start();
      try(HttpClient client = EmailHttp.newClient())
      {
         EmailRuntime.setClock(Clock.fixed(now, ZoneOffset.UTC));
         H2Support.setup(config -> config.withSecretResolver(name ->
         {
            resolutions.incrementAndGet();
            return (SECRET);
         }));
         H2Support.insert(EmailTables.PROVIDER, row(kind).withValue("name", "http").withValue("label", "HTTP")
            .withValue("enabled", true).withValue("isDefault", true));
         var queued = EmailService.send(new EmailRequest().withTo("to@example.com").withCc("cc@example.com").withBcc("bcc@example.com")
            .withSubject("HTTP integration").withTextBody("body"));
         assertThat(queued.status()).isEqualTo(EmailSendResult.Status.QUEUED);
         assertThat(resolutions.get()).isZero();
         assertThat(received.get()).isNull();
         EmailHttp local = request ->
         {
            // Only the test seam rewrites the fixed production URL to a local server.
            HttpRequest.Builder localRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + request.uri().getPath()))
               .timeout(request.timeout().orElseThrow()).POST(request.bodyPublisher().orElseThrow());
            request.headers().map().forEach((name, values) -> values.forEach(value -> localRequest.header(name, value)));
            return (client.send(localRequest.build(), HttpResponse.BodyHandlers.ofString()));
         };
         var dispatcher = new EmailDispatcher(new EmailTransportRegistry().withTransport(kind, transport(kind, local)));
         dispatcher.runOnce();
         assertThat(resolutions.get()).isEqualTo(1);
         assertThat(received.get()).contains("to@example.com", "bcc@example.com").doesNotContain(SECRET);
         QRecord message = EmailStore.get(EmailTables.MESSAGE, queued.messageId(), null);
         QRecord attempt = H2Support.all(EmailTables.ATTEMPT).getFirst();
         QRecord provider = H2Support.all(EmailTables.PROVIDER).getFirst();
         assertThat(message.getValueString("status")).isEqualTo(expectedStatus);
         if("SENT".equals(expectedStatus))
         {
            assertThat(message.getValueString("providerMessageId")).isEqualTo("http-id");
            assertThat(attempt.getValueString("outcome")).isEqualTo("SUCCEEDED");
            assertThat(message.getValueString("lastError")).isNull();
         }
         else
         {
            assertThat(message.getValueString("lastError")).doesNotContain(SECRET, "private-recipient", ECHO);
            assertThat(attempt.getValueString("error")).isEqualTo(message.getValueString("lastError"));
            assertThat(provider.getValueString("lastFailureMessage")).isEqualTo(message.getValueString("lastError"));
            if(status == 401 || status == 403)
            {
               assertThat(provider.getValueString("healthStatus")).isEqualTo("UNHEALTHY");
               assertThat(attempt.getValueString("outcome")).isEqualTo("PROVIDER_FAILURE");
            }
            else
            {
               assertThat(attempt.getValueString("outcome")).isEqualTo("DEAD_LETTER".equals(expectedStatus) ? "PERMANENT_FAILURE" : "TRANSIENT_FAILURE");
            }
            if("RETRY_WAIT".equals(expectedStatus))
            {
               assertThat(message.getValueInstant("nextAttemptDate")).isEqualTo(now.plusSeconds(123));
            }
         }
      }
      finally
      {
         server.stop(0);
         QContext.clear();
         EmailRuntime.setClock(null);
      }
   }
}

