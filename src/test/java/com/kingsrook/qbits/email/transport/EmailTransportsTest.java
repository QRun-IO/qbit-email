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
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.H2Support;
import com.kingsrook.qbits.email.delivery.EmailDispatcher;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailRequest;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.*;
import static org.assertj.core.api.Assertions.*;

/*******************************************************************************
 ** Email Transports Test.
 *******************************************************************************/
class EmailTransportsTest
{
   /*******************************************************************************
    ** Envelope.
    *******************************************************************************/
   EmailEnvelope envelope()
   {
      return (new EmailEnvelope()
            .withMessageId("123")
            .withFrom("sender@example.com")
            .withTo(List.of("a@example.com"))
            .withBcc(List.of("hidden@example.com"))
            .withReplyTo("reply@example.com")
            .withSubject("Subject")
            .withHtmlBody("<p>HTML</p>")
            .withTextBody("Text")
            .withHeaders(Map.of("X-Test", "yes")));
   }

   /*******************************************************************************
    ** Settings.
    *******************************************************************************/
   EmailProviderSettings settings()
   {
      return (new EmailProviderSettings(
            new QRecord()
                  .withValue("kind", "SES")
                  .withValue("region", "us-east-1")
                  .withValue("configurationSet", "events")
                  .withValue("usernameEnvName", "AWS_ACCESS_KEY_ID")
                  .withValue("secretEnvName", "AWS_SECRET_ACCESS_KEY"),
            new EmailQBitConfig().withSecretResolver(n -> "test-only")));
   }

   /*******************************************************************************
    ** Client.
    *******************************************************************************/
   SesV2Client client(java.util.function.Function<SendEmailRequest, SendEmailResponse> send)
   {
      return ((SesV2Client)
            Proxy.newProxyInstance(
                  getClass().getClassLoader(),
                  new Class<?>[] {SesV2Client.class},
                  (proxy, method, args) ->
                  {
                     if(method.getName().equals("sendEmail"))
                     {
                        return (send.apply((SendEmailRequest) args[0]));
                     }
                     if(method.getName().equals("serviceName"))
                     {
                        return ("sesv2");
                     }
                     return (null);
                  }));
   }

   /*******************************************************************************
    ** Ses Success Preserves Mime And Does Not Expose Bcc.
    *******************************************************************************/
   @Test
   void sesSuccessPreservesMimeAndDoesNotExposeBcc() throws Exception
   {
      AtomicReference<SendEmailRequest> captured = new AtomicReference<>();
      var transport =
            new SesEmailTransport(
                  builder ->
                        client(
                              request ->
                              {
                                 captured.set(request);
                                 return (SendEmailResponse.builder().messageId("ses-id").build());
                              }));
      assertThat(transport.send(envelope().withTo(List.of("Pat <a@example.com>, b@example.com")), settings()).providerMessageId()).isEqualTo("ses-id");
      assertThat(captured.get().destination().toAddresses()).containsExactly("a@example.com", "b@example.com");
      assertThat(captured.get().configurationSetName()).isEqualTo("events");
      assertThat(captured.get().destination().bccAddresses()).containsExactly("hidden@example.com");
      var mime =
            new MimeMessage(
                  Session.getInstance(new Properties()),
                  new ByteArrayInputStream(captured.get().content().raw().data().asByteArray()));
      assertThat(mime.getHeader("Bcc")).isNull();
      assertThat(mime.getHeader("X-Test")).containsExactly("yes");
      assertThat(mime.getHeader("Auto-Submitted")).containsExactly("auto-generated");
   }

   /*******************************************************************************
    ** Ses Classifies Throttling Rejection And Server Failure.
    *******************************************************************************/
   @Test
   void sesClassifiesThrottlingRejectionAndServerFailure() throws Exception
   {
      for(var error :
            List.of(
                  TooManyRequestsException.builder().statusCode(429).message("secret").build(),
                  MessageRejectedException.builder().statusCode(400).message("secret").build(),
                  SesV2Exception.builder().statusCode(503).message("secret").build()))
                  {
         var transport =
               new SesEmailTransport(
                     builder ->
                           client(
                                 request ->
                                 {
                                    throw error;
                                 }));
         try
         {
            transport.send(envelope(), settings());
            fail("Expected failure");
         }
         catch(EmailTransportException actual)
         {
            assertThat(actual.getPermanent()).isEqualTo(error.statusCode() == 400);
            assertThat(actual.getMessage()).doesNotContain("secret");
            assertThat(actual.getCause()).isNull();
         }
      }
      var defaultChain =
            new EmailProviderSettings(
                  new QRecord().withValue("kind", "SES").withValue("region", "us-east-1"),
                  new EmailQBitConfig());
      assertThat(
                  new SesEmailTransport(
                              builder ->
                                    client(r -> SendEmailResponse.builder().messageId("default").build()))
                        .send(envelope(), defaultChain)
                        .providerMessageId())
            .isEqualTo("default");
      assertThatThrownBy(
                  () ->
                        new SesEmailTransport(
                                    b ->
                                    {
                                       throw new IllegalArgumentException("secret");
                                    })
                              .send(envelope(), settings()))
            .hasMessage("SES temporarily unavailable");
   }

   /*******************************************************************************
    ** Smtp Classifies Nested Replies.
    *******************************************************************************/
   @Test
   void smtpClassifiesNestedReplies() throws Exception
   {
      var permanent =
            new SMTPAddressFailedException(
                  new InternetAddress("a@example.com"), "RCPT TO", 550, "secret recipient");
      var transientError =
            new SMTPAddressFailedException(
                  new InternetAddress("a@example.com"), "RCPT TO", 450, "temporary");
      var wrapper = new SendFailedException("failed");
      wrapper.setNextException(permanent);
      assertThat(SmtpEmailTransport.permanent(wrapper)).isTrue();
      assertThat(SmtpEmailTransport.permanent(transientError)).isFalse();
      assertThat(
                  SmtpEmailTransport.permanent(
                        new MessagingException("timeout", new java.net.SocketTimeoutException())))
            .isFalse();
      assertThat(SmtpEmailTransport.permanent(new jakarta.mail.AuthenticationFailedException("454 Temporary authentication failure"))).isFalse();
      assertThat(SmtpEmailTransport.permanent(new jakarta.mail.AuthenticationFailedException()))
            .isFalse();
      assertThat(SmtpEmailTransport.providerLevel(new jakarta.mail.AuthenticationFailedException("535 Authentication refused"))).isTrue();
      assertThat(
                  SmtpEmailTransport.permanent(
                        new SMTPSendFailedException("DATA", 550, "failed", null, null, null, null)))
            .isTrue();
   }

   /*******************************************************************************
    ** Account and credential errors are provider failures, not message rejections.
    *******************************************************************************/
   @Test
   void sesAccountFailuresAreRetryable() throws Exception
   {
      for(String code : List.of("AccountSuspended", "SendingPaused", "MailFromDomainNotVerified", "InvalidClientTokenId", "SignatureDoesNotMatch"))
      {
         assertSesFailure(400, code, true, false);
      }
      assertSesFailure(401, "Unauthorized", true, false);
      assertSesFailure(403, "AccessDenied", true, false);
      assertSesFailure(400, "InvalidParameterValue", false, true);
      assertSesFailure(400, "MessageRejected", false, true);
      assertSesFailure(429, "TooManyRequests", false, false);
      assertSesFailure(503, "ServiceUnavailable", false, false);
   }

   /*******************************************************************************
    ** SES 403 is persisted as an unhealthy provider and recovers without a dead letter.
    *******************************************************************************/
   @Test
   void sesForbiddenPausesProviderAndRecovers() throws Exception
   {
      var now = Instant.parse("2026-10-13T12:00:00Z");
      try
      {
         H2Support.setup(config -> config.withMaxAttempts(1));
         EmailRuntime.setClock(Clock.fixed(now, ZoneOffset.UTC));
         H2Support.insert(EmailTables.PROVIDER,
            new QRecord().withValue("kind", "SES").withValue("name", "ses").withValue("region", "us-east-1")
               .withValue("fromAddress", "sender@example.com").withValue("enabled", true).withValue("isDefault", true));
         var queued = EmailService.send(new EmailRequest()
            .withTo("a@example.com").withSubject("subject").withTextBody("body"));
         AtomicReference<Boolean> forbidden = new AtomicReference<>(true);
         var transport = new SesEmailTransport(builder -> client(request ->
         {
            if(forbidden.get())
            {
               throw SesV2Exception.builder().statusCode(403).message("private diagnostic").build();
            }
            return (SendEmailResponse.builder().messageId("ses-ok").build());
         }));
         var dispatcher = new EmailDispatcher(new EmailTransportRegistry().withTransport("SES", transport));
         assertThat(dispatcher.runOnce().failed()).isEqualTo(1);
         assertThat(H2Support.all(EmailTables.PROVIDER).getFirst().getValueString("healthStatus"))
            .isEqualTo("UNHEALTHY");
         assertThat(H2Support.all(EmailTables.MESSAGE).getFirst().getValueString("status"))
            .isEqualTo("RETRY_WAIT");
         forbidden.set(false);
         EmailRuntime.setClock(Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC));
         assertThat(dispatcher.sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.SENT);
         assertThat(H2Support.all(EmailTables.PROVIDER).getFirst().getValueString("healthStatus"))
            .isEqualTo("HEALTHY");
      }
      finally
      {
         QContext.clear();
         EmailRuntime.setClock(null);
      }
   }

   /*******************************************************************************
    ** Exercise classification through the real SES send path with a fake client.
    *******************************************************************************/
   private void assertSesFailure(int status, String code, boolean provider, boolean permanent)
   {
      var transport = new SesEmailTransport(builder -> client(request ->
      {
         throw SesV2Exception.builder().statusCode(status).message("private diagnostic")
            .awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder().errorCode(code).build()).build();
      }));
      assertThatThrownBy(() -> transport.send(envelope(), settings())).isInstanceOfSatisfying(EmailTransportException.class, error ->
      {
         assertThat(error.getProviderLevel()).isEqualTo(provider);
         assertThat(error.getPermanent()).isEqualTo(permanent);
         assertThat(error.getMessage()).doesNotContain("private diagnostic");
         assertThat(error.getCause()).isNull();
      });
   }

   /*******************************************************************************
    ** MIME construction refuses CR and LF in every address role and the subject.
    *******************************************************************************/
   @Test
   void mimeRejectsHeaderInjectionAndBuildFailuresArePermanent()
   {
      for(String newline : List.of("\r", "\n"))
      {
         String bad = "a@example.com" + newline + "Bcc: hidden@example.com";
         for(EmailEnvelope envelope : List.of(envelope().withSubject(bad), envelope().withFrom(bad), envelope().withReplyTo(bad),
            envelope().withTo(List.of(bad)), envelope().withCc(List.of(bad)), envelope().withBcc(List.of(bad)),
            envelope().withHeaders(Map.of("X-Test", bad)), envelope().withFrom("broken <")))
         {
            assertThatThrownBy(() -> EmailMime.build(Session.getInstance(new Properties()), envelope)).isInstanceOf(MessagingException.class);
            var smtp = new EmailProviderSettings(new QRecord().withValue("kind", "SMTP").withValue("host", "127.0.0.1")
               .withValue("port", 1).withValue("security", "NONE"), new EmailQBitConfig());
            for(EmailTransport transport : List.of(new SmtpEmailTransport(), new SesEmailTransport(builder ->
            {
               throw new AssertionError("Invalid MIME must not reach the client");
            })))
            {
               assertThatThrownBy(() -> transport.send(envelope, transport instanceof SmtpEmailTransport ? smtp : settings()))
                  .isInstanceOfSatisfying(EmailTransportException.class, error ->
                  {
                     assertThat(error.getPermanent()).isTrue();
                     assertThat(error.getProviderLevel()).isFalse();
                  });
            }
         }
      }
   }

   /*******************************************************************************
    ** Guard legacy rows before resolving any secret or opening a connection.
    *******************************************************************************/
   @Test
   void smtpBlocksPlaintextAuthenticationBeforeConnecting()
   {
      for(String kind : List.of("SMTP", "GMAIL_SMTP"))
      {
         var row = new QRecord().withValue("kind", kind).withValue("host", "relay.example.com").withValue("port", 25)
            .withValue("security", "NONE").withValue("usernameEnvName", "EMAIL_SMTP_USERNAME");
         var config = new EmailQBitConfig().withSecretResolver(name ->
         {
            throw new AssertionError("Must reject before resolving credentials");
         });
         assertThatThrownBy(() -> new SmtpEmailTransport().send(envelope(), new EmailProviderSettings(row, config)))
            .hasMessage("SMTP sign-in needs STARTTLS or TLS").isInstanceOfSatisfying(EmailTransportException.class, error ->
            {
               assertThat(error.getProviderLevel()).isTrue();
               assertThat(error.getPermanent()).isFalse();
            });
      }
   }

   /*******************************************************************************
    ** Settings Validate References And Missing Secrets.
    *******************************************************************************/
   @Test
   void settingsValidateReferencesAndMissingSecrets() throws Exception
   {
      var gmail =
            new EmailProviderSettings(
                  new QRecord().withValue("kind", "GMAIL_SMTP"), new EmailQBitConfig());
      assertThat(gmail.host()).isEqualTo("smtp.gmail.com");
      assertThat(gmail.port()).isEqualTo(587);
      assertThat(gmail.security()).isEqualTo("STARTTLS");
      var row = new QRecord().withValue("kind", "SMTP").withValue("secretEnvName", "INVALID");
      assertThatThrownBy(
                  () -> new EmailProviderSettings(row, new EmailQBitConfig()).resolve("secretEnvName"))
            .hasMessageContaining("not allowed").isInstanceOfSatisfying(EmailTransportException.class, error ->
            {
               assertThat(error.getProviderLevel()).isTrue();
               assertThat(error.getPermanent()).isFalse();
            });
      row.withValue("secretEnvName", "EMAIL_SMTP_PASSWORD");
      assertThatThrownBy(
                  () ->
                        new EmailProviderSettings(row, new EmailQBitConfig().withSecretResolver(n -> null))
                              .resolve("secretEnvName"))
            .hasMessageContaining("not set").isInstanceOfSatisfying(EmailTransportException.class, error ->
            {
               assertThat(error.getProviderLevel()).isTrue();
               assertThat(error.getPermanent()).isFalse();
            });
      assertThatThrownBy(
                  () ->
                        new EmailProviderSettings(
                                    row,
                                    new EmailQBitConfig()
                                          .withSecretResolver(
                                                n ->
                                                {
                                                   throw new RuntimeException("secret");
                                                }))
                              .resolve("secretEnvName"))
            .hasMessageContaining("not set");
      assertThatThrownBy(
                  () ->
                        EmailMime.build(
                              Session.getInstance(new Properties()),
                              envelope().withHeaders(Map.of("X-Header", "bad\r\nvalue"))))
            .isInstanceOf(MessagingException.class);
   }
}
