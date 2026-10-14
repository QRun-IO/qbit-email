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

package com.kingsrook.qbits.email;

import java.net.ServerSocket;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.kingsrook.qbits.email.delivery.*;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.*;
import com.kingsrook.qbits.email.processes.EmailProcesses;
import com.kingsrook.qbits.email.support.*;
import com.kingsrook.qbits.email.transport.*;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.messaging.SendMessageAction;
import com.kingsrook.qqq.backend.core.alerts.AlertResolution;
import com.kingsrook.qqq.backend.core.alerts.AlertSignal;
import com.kingsrook.qqq.backend.core.alerts.AlertSinkInterface;
import com.kingsrook.qqq.backend.core.alerts.AlertsInstanceMetaData;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.messaging.*;
import com.kingsrook.qqq.backend.core.model.actions.messaging.email.*;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/*******************************************************************************
 ** Email Delivery Test.
 *******************************************************************************/
class EmailDeliveryTest
{
   GreenMail mail;
   Instant now = Instant.parse("2026-10-13T12:00:00Z");

   /*******************************************************************************
    ** Setup.
    *******************************************************************************/
   @BeforeEach
   void setup() throws Exception
   {
      EmailRuntime.setClock(Clock.fixed(now, ZoneOffset.UTC));
      H2Support.setup(c ->
      {
      });
      mail = new GreenMail(new ServerSetup(0, "127.0.0.1", "smtp"));
      mail.start();
      H2Support.provider(mail.getSmtp().getPort());
   }

   /*******************************************************************************
    ** Cleanup.
    *******************************************************************************/
   @AfterEach
   void cleanup()
   {
      if(mail != null)
      {
         mail.stop();
      }
      QContext.clear();
      EmailRuntime.setClock(null);
   }

   /*******************************************************************************
    ** Request.
    *******************************************************************************/
   EmailRequest request()
   {
      return (new EmailRequest()
            .withTo("Person <person@example.com>")
            .withSubject("Hello")
            .withMarkdownBody("# Hello\n\nA **message**."));
   }

   /*******************************************************************************
    ** Message.
    *******************************************************************************/
   QRecord message(Integer id) throws Exception
   {
      return (EmailStore.get(EmailTables.MESSAGE, id, null));
   }

   /*******************************************************************************
    ** Fake.
    *******************************************************************************/
   EmailDispatcher fake(EmailTransport transport)
   {
      return (new EmailDispatcher(new EmailTransportRegistry().withTransport("SMTP", transport)));
   }

   /*******************************************************************************
    ** Missing credentials and SMTP 535 pause a provider until credentials recover.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void providerCredentialsRecoverWithoutExhaustingMessageBudget(boolean refused) throws Exception
   {
      mail.setUser("person@example.com", "login", "password");
      EmailRuntime.config().withMaxAttempts(1).withSecretResolver(name -> refused ? "wrong" : null);
      SystemWrites.updateOne(EmailTables.PROVIDER, new QRecord().withValue("id", 1)
         .withValue("usernameEnvName", "EMAIL_SMTP_USERNAME").withValue("secretEnvName", "EMAIL_SMTP_PASSWORD"));
      var first = EmailService.send(request());
      var second = EmailService.send(request());
      var dispatcher = new EmailDispatcher();
      for(int i = 0; i < 2; i++)
      {
         EmailRuntime.setClock(Clock.fixed(now.plusSeconds(i * 301L), ZoneOffset.UTC));
         assertThat(dispatcher.runOnce()).isEqualTo(new EmailDispatcher.RunSummary(0, 1, 1));
         assertThat(message(first.messageId()).getValueString("status")).isEqualTo("RETRY_WAIT");
         assertThat(message(first.messageId()).getValueInteger("attemptCount")).isEqualTo(i + 1);
         assertThat(message(first.messageId()).getValueInstant("nextAttemptDate"))
            .isEqualTo(now.plusSeconds(i * 301L + (i == 0 ? 60 : 300)));
         assertThat(message(second.messageId()).getValueInteger("attemptCount")).isZero();
         var provider = H2Support.all(EmailTables.PROVIDER).getFirst();
         assertThat(provider.getValueString("healthStatus")).isEqualTo("UNHEALTHY");
         assertThat(provider.getValueString("lastFailureMessage")).isEqualTo("Credentials missing or refused");
      }
      EmailRuntime.config().withSecretResolver(name -> name.endsWith("USERNAME") ? "login" : "password");
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(602), ZoneOffset.UTC));
      assertThat(dispatcher.runOnce().sent()).isEqualTo(2);
      assertThat(message(first.messageId()).getValueString("status")).isEqualTo("SENT");
      assertThat(H2Support.all(EmailTables.PROVIDER).getFirst().getValueString("healthStatus")).isEqualTo("HEALTHY");
      assertThat(H2Support.all(EmailTables.ATTEMPT)).extracting(row -> row.getValueString("outcome"))
         .containsExactly("PROVIDER_FAILURE", "PROVIDER_FAILURE", "SUCCEEDED", "SUCCEEDED");
   }

   /*******************************************************************************
    ** Ordinary transient failures still have their full budget after provider repair.
    *******************************************************************************/
   @Test
   void providerFailuresDoNotConsumeLaterTransientBudget() throws Exception
   {
      EmailRuntime.config().withMaxAttempts(2);
      var queued = EmailService.send(request());
      var broken = fake((e, settings) ->
      {
         throw new EmailTransportException("credentials", false).withProviderLevel(true);
      });
      for(int i = 0; i < 3; i++)
      {
         EmailRuntime.setClock(Clock.fixed(now.plusSeconds(i * 901L), ZoneOffset.UTC));
         assertThat(broken.sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.RETRY_WAIT);
      }
      var temporary = fake((e, settings) ->
      {
         throw new EmailTransportException("temporary", false);
      });
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(2703), ZoneOffset.UTC));
      assertThat(temporary.sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.RETRY_WAIT);
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(3004), ZoneOffset.UTC));
      assertThat(temporary.sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.DEAD_LETTER);
      assertThat(message(queued.messageId()).getValueInteger("attemptCount")).isEqualTo(5);
   }

   /*******************************************************************************
    ** Both lease recovery paths exclude earlier provider failures from the budget.
    *******************************************************************************/
   @ParameterizedTest
   @ValueSource(booleans = {false, true})
   void leaseRecoveryExcludesProviderFailures(boolean maintenance) throws Exception
   {
      EmailRuntime.config().withMaxAttempts(2);
      var queued = EmailService.send(request());
      for(int i = 0; i < 2; i++)
      {
         EmailRuntime.setClock(Clock.fixed(now.plusSeconds(i * 301L), ZoneOffset.UTC));
         assertThat(fake((e, settings) ->
         {
            throw new EmailTransportException("credentials", false).withProviderLevel(true);
         }).sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.RETRY_WAIT);
      }
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(602), ZoneOffset.UTC));
      var slow = fake((e, settings) ->
      {
         EmailRuntime.setClock(Clock.fixed(now.plusSeconds(903), ZoneOffset.UTC));
         var recovery = fake((envelope, provider) -> new EmailTransportResult("recovered"));
         try
         {
            assertThat((maintenance ? recovery.maintain() : recovery.runOnce()).sent()).isEqualTo(1);
         }
         catch(Exception ex)
         {
            throw new RuntimeException(ex);
         }
         return (new EmailTransportResult("late"));
      });
      assertThat(slow.sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.NOT_SENT);
      assertThat(message(queued.messageId()).getValueString("providerMessageId")).isEqualTo("recovered");
      assertThat(H2Support.all(EmailTables.ATTEMPT)).extracting(row -> row.getValueString("outcome"))
         .containsExactly("PROVIDER_FAILURE", "PROVIDER_FAILURE", "LEASE_EXPIRED", "SUCCEEDED");
   }

   /*******************************************************************************
    ** Even a slow tick stops the failed provider while processing another provider.
    *******************************************************************************/
   @Test
   void providerFailureStopsTheTickAfterProbeWindowPasses() throws Exception
   {
      EmailService.send(request());
      EmailService.send(request());
      H2Support.insert(EmailTables.PROVIDER, new QRecord().withValue("name", "other").withValue("kind", "SMTP")
         .withValue("host", "localhost").withValue("port", 25).withValue("fromAddress", "sender@example.com")
         .withValue("enabled", true).withValue("isDefault", false));
      var other = EmailService.send(request().withProviderName("other"));
      AtomicInteger calls = new AtomicInteger();
      var dispatcher = new EmailDispatcher(new EmailTransportRegistry().withTransport("SMTP", (e, settings) ->
      {
         calls.incrementAndGet();
         if("relay".equals(settings.value("name")))
         {
            throw new EmailTransportException("credentials", false).withProviderLevel(true);
         }
         return (new EmailTransportResult("ok"));
      }))
      {
         /*******************************************************************************
          ** Advance time only after the failed attempt has committed.
          *******************************************************************************/
         @Override
         protected SendResult attempt(Integer id, boolean immediate) throws QException
         {
            SendResult result = super.attempt(id, immediate);
            EmailRuntime.setClock(Clock.fixed(now.plusSeconds(601), ZoneOffset.UTC));
            return (result);
         }
      };
      assertThat(dispatcher.runOnce()).isEqualTo(new EmailDispatcher.RunSummary(1, 1, 1));
      assertThat(calls.get()).isEqualTo(2);
      assertThat(message(other.messageId()).getValueString("status")).isEqualTo("SENT");
   }

   /*******************************************************************************
    ** Corrupt queued MIME is dead-lettered and does not prevent the next delivery.
    *******************************************************************************/
   @Test
   void messageBuildFailureIsPermanent() throws Exception
   {
      var invalidAddress = EmailService.send(request());
      var invalidSubject = EmailService.send(request());
      var valid = EmailService.send(request());
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", invalidAddress.messageId()).withValue("fromAddress", "broken <"));
      SystemWrites.updateOne(EmailTables.MESSAGE, new QRecord().withValue("id", invalidSubject.messageId()).withValue("subject", "bad\r\nheader"));
      assertThat(new EmailDispatcher().runOnce()).isEqualTo(new EmailDispatcher.RunSummary(1, 2, 0));
      assertThat(message(invalidAddress.messageId()).getValueString("status")).isEqualTo("DEAD_LETTER");
      assertThat(message(invalidSubject.messageId()).getValueString("status")).isEqualTo("DEAD_LETTER");
      assertThat(message(valid.messageId()).getValueString("status")).isEqualTo("SENT");
   }

   /*******************************************************************************
    ** A database or runtime failure on one message cannot abort a batch.
    *******************************************************************************/
   @Test
   void isolatesCheckedAndRuntimeAttemptFailures() throws Exception
   {
      var checked = EmailService.send(request());
      var unchecked = EmailService.send(request());
      var valid = EmailService.send(request());
      var dispatcher = new EmailDispatcher()
      {
         /*******************************************************************************
          ** Inject failures at the same boundary as database claim/completion errors.
          *******************************************************************************/
         @Override
         protected SendResult attempt(Integer id, boolean immediate) throws QException
         {
            if(id.equals(checked.messageId()))
            {
               throw new QException("test database failure");
            }
            if(id.equals(unchecked.messageId()))
            {
               throw new IllegalStateException("test runtime failure");
            }
            return (super.attempt(id, immediate));
         }
      };
      assertThat(dispatcher.runOnce()).isEqualTo(new EmailDispatcher.RunSummary(1, 2, 0));
      assertThat(message(valid.messageId()).getValueString("status")).isEqualTo("SENT");
   }

   /*******************************************************************************
    ** Both unhealthy raises and recovery resolutions tolerate a throwing sink.
    *******************************************************************************/
   @Test
   void failingAlertSinkCannotFailCommittedOutcomes() throws Exception
   {
      ThrowingAlertSink.raised = 0;
      ThrowingAlertSink.resolved = 0;
      AlertsInstanceMetaData.ofOrWithNew(QContext.getQInstance())
         .withSink(new QCodeReference(ThrowingAlertSink.class));
      var queued = EmailService.send(request());
      assertThat(fake((e, settings) ->
      {
         throw new EmailTransportException("credentials", false).withProviderLevel(true);
      }).sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.RETRY_WAIT);
      assertThat(ThrowingAlertSink.raised).isEqualTo(1);
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC));
      assertThat(new EmailDispatcher().sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.SENT);
      assertThat(ThrowingAlertSink.resolved).isEqualTo(1);
      assertThat(message(queued.messageId()).getValueString("status")).isEqualTo("SENT");
      var rejected = EmailService.send(request());
      assertThat(fake((e, settings) ->
      {
         throw new EmailTransportException("rejected", true);
      }).sendNow(rejected.messageId())).isEqualTo(EmailDispatcher.SendResult.DEAD_LETTER);
      assertThat(ThrowingAlertSink.raised).isEqualTo(2);
   }

   /*******************************************************************************
    ** Loadable sink that records invocation before simulating an alert outage.
    *******************************************************************************/
   public static class ThrowingAlertSink implements AlertSinkInterface
   {
      static int raised;
      static int resolved;

      /*******************************************************************************
       ** Simulate a failed alert raise.
       *******************************************************************************/
      @Override
      public void raise(AlertSignal signal)
      {
         raised++;
         throw new IllegalStateException("test alert outage");
      }

      /*******************************************************************************
       ** Simulate a failed recovery resolution.
       *******************************************************************************/
      @Override
      public void resolve(AlertResolution resolution)
      {
         resolved++;
         throw new IllegalStateException("test alert outage");
      }

      /*******************************************************************************
       ** Heartbeats are not used by email delivery.
       *******************************************************************************/
      @Override
      public void heartbeat(String source, Instant timestamp)
      {
      }
   }

   /*******************************************************************************
    ** Smtp End To End And Idempotency.
    *******************************************************************************/
   @Test
   void smtpEndToEndAndIdempotency() throws Exception
   {
      var queued = EmailService.send(request().withIdempotencyKey("one"));
      assertThat(queued.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      assertThat(mail.getReceivedMessages()).isEmpty();
      var duplicate = EmailService.send(request().withIdempotencyKey("one"));
      assertThat(duplicate.status()).isEqualTo(EmailSendResult.Status.DUPLICATE);
      assertThat(duplicate.messageId()).isEqualTo(queued.messageId());
      assertThat(new EmailDispatcher().runOnce().sent()).isEqualTo(1);
      assertThat(mail.waitForIncomingEmail(2000, 1)).isTrue();
      var received = mail.getReceivedMessages()[0];
      Multipart parts = (Multipart) received.getContent();
      assertThat(parts.getCount()).isEqualTo(2);
      assertThat(parts.getBodyPart(0).getContent().toString()).contains("Hello", "message");
      assertThat(parts.getBodyPart(1).getContent().toString()).contains("<strong>message</strong>");
      assertThat(received.getHeader("Auto-Submitted")).containsExactly("auto-generated");
      assertThat(received.getHeader("X-QQQ-Email-Id")).containsExactly(queued.messageId().toString());
      assertThat(message(queued.messageId()).getValueString("providerMessageId"))
            .isEqualTo(received.getMessageID());
      assertThat(message(queued.messageId()).getValueString("status")).isEqualTo("SENT");
      assertThat(H2Support.all(EmailTables.ATTEMPT)).hasSize(1);
   }

   /*******************************************************************************
    ** Smtp Authentication And Secret Resolution Only At Send.
    *******************************************************************************/
   @Test
   void smtpAuthenticationAndSecretResolutionOnlyAtSend() throws Exception
   {
      mail.setUser("person@example.com", "login", "password");
      AtomicInteger resolutions = new AtomicInteger();
      EmailRuntime.config()
            .withSecretResolver(
                  name ->
                  {
                     resolutions.incrementAndGet();
                     return (name.endsWith("USERNAME") ? "login" : "password");
                  });
      SystemWrites.updateOne(
            EmailTables.PROVIDER,
            new QRecord()
                  .withValue("id", 1)
                  .withValue("usernameEnvName", "EMAIL_SMTP_USERNAME")
                  .withValue("secretEnvName", "EMAIL_SMTP_PASSWORD"));
      var queued = EmailService.send(request());
      assertThat(resolutions.get()).isZero();
      assertThat(new EmailDispatcher().sendNow(queued.messageId()))
            .isEqualTo(EmailDispatcher.SendResult.SENT);
      assertThat(resolutions.get()).isEqualTo(2);
      assertThat(mail.waitForIncomingEmail(2000, 1)).isTrue();
   }

   /*******************************************************************************
    ** Unreachable Smtp Retries.
    *******************************************************************************/
   @Test
   void unreachableSmtpRetries() throws Exception
   {
      int port;
      try(ServerSocket socket = new ServerSocket(0))
      {
         port = socket.getLocalPort();
      }
      SystemWrites.updateOne(
            EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("port", port));
      var queued = EmailService.send(request());
      assertThat(new EmailDispatcher().sendNow(queued.messageId()))
            .isEqualTo(EmailDispatcher.SendResult.RETRY_WAIT);
      assertThat(message(queued.messageId()).getValueInstant("nextAttemptDate"))
            .isEqualTo(now.plusSeconds(60));
      assertThat(message(queued.messageId()).getValueString("lastError"))
            .doesNotContain("person", "Hello");
   }

   /*******************************************************************************
    ** Retries Backoff Max Attempts And Retry After.
    *******************************************************************************/
   @Test
   void retriesBackoffMaxAttemptsAndRetryAfter() throws Exception
   {
      EmailRuntime.config().withMaxAttempts(3);
      var queued = EmailService.send(request());
      var dispatcher =
            fake(
                  (e, s) ->
                  {
                     throw new EmailTransportException("secret diagnostic", false);
                  });
      assertThat(dispatcher.runOnce().failed()).isEqualTo(1);
      assertThat(message(queued.messageId()).getValueInstant("nextAttemptDate"))
            .isEqualTo(now.plusSeconds(60));
      assertThat(dispatcher.runOnce().failed()).isZero();
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC));
      dispatcher.runOnce();
      assertThat(message(queued.messageId()).getValueInstant("nextAttemptDate"))
            .isEqualTo(now.plusSeconds(360));
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(360), ZoneOffset.UTC));
      assertThat(dispatcher.runOnce().failed()).isEqualTo(1);
      assertThat(message(queued.messageId()).getValueString("status")).isEqualTo("DEAD_LETTER");
      var second = EmailService.send(request());
      fake((e, s) ->
      {
               throw new EmailTransportException("throttle", false).withRetryAfter(Duration.ofHours(2));
            })
            .runOnce();
      assertThat(message(second.messageId()).getValueInstant("nextAttemptDate"))
            .isEqualTo(now.plusSeconds(3960));
   }

   /*******************************************************************************
    ** Permanent Failure And Future Transport Are Dead Letters.
    *******************************************************************************/
   @Test
   void permanentFailureAndFutureTransportAreDeadLetters() throws Exception
   {
      var queued = EmailService.send(request());
      assertThat(
                  fake((e, s) ->
                  {
                           throw new EmailTransportException("rejected", true);
                        })
                        .sendNow(queued.messageId()))
            .isEqualTo(EmailDispatcher.SendResult.DEAD_LETTER);
      var future = EmailService.send(request());
      SystemWrites.updateOne(
            EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("kind", "GMAIL_API"));
      assertThat(new EmailDispatcher().sendNow(future.messageId()))
            .isEqualTo(EmailDispatcher.SendResult.DEAD_LETTER);
      assertThatThrownBy(() -> new EmailTransportRegistry().get("GMAIL_API"))
            .hasMessage("Gmail API sending is specified but not built; use GMAIL_SMTP");
   }

   /*******************************************************************************
    ** Expired Lease Retries And Stale Completion Cannot Overwrite.
    *******************************************************************************/
   @Test
   void expiredLeaseRetriesAndStaleCompletionCannotOverwrite() throws Exception
   {
      var queued = EmailService.send(request());
      var recovery = fake((e, s) -> new EmailTransportResult("recovered"));
      var slow =
            fake(
                  (e, s) ->
                  {
                     EmailRuntime.setClock(Clock.fixed(now.plusSeconds(121), ZoneOffset.UTC));
                     try
                     {
                        assertThat(recovery.runOnce().sent()).isEqualTo(1);
                     }
                     catch(Exception ex)
                     {
                        throw new RuntimeException(ex);
                     }
                     return (new EmailTransportResult("late"));
                  });
      assertThat(slow.sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.NOT_SENT);
      assertThat(message(queued.messageId()).getValueString("providerMessageId"))
            .isEqualTo("recovered");
      assertThat(H2Support.all(EmailTables.ATTEMPT))
            .extracting(r -> r.getValueString("outcome"))
            .containsExactly("LEASE_EXPIRED", "SUCCEEDED");
   }

   /*******************************************************************************
    ** Suppression Scope Category Expiry And Partial.
    *******************************************************************************/
   @Test
   void suppressionScopeCategoryExpiryAndPartial() throws Exception
   {
      EmailRuntime.config().withPublicBaseUrl("https://app.example.com").withSecretResolver(name -> "test-unsubscribe-key");
      H2Support.insert(EmailTables.BRAND, new QRecord().withValue("name", "postal").withValue("isDefault", true).withValue("postalAddress", "123 Test Street"));
      H2Support.insert(
            EmailTables.SUPPRESSION,
            new QRecord()
                  .withValue("address", "PERSON@example.com")
                  .withValue("scope", "ALL")
                  .withValue("reason", "HARD_BOUNCE"));
      var suppressed = EmailService.send(request());
      assertThat(suppressed.status()).isEqualTo(EmailSendResult.Status.SUPPRESSED);
      assertThat(new EmailDispatcher().runOnce().sent()).isZero();
      var partial = EmailService.send(request().withCc("other@example.com"));
      assertThat(partial.status()).isEqualTo(EmailSendResult.Status.QUEUED);
      assertThat(message(partial.messageId()).getValueString("toAddresses")).isEmpty();
      assertThat(message(partial.messageId()).getValueString("suppressedAddresses"))
            .contains("person@example.com");
      SystemWrites.delete(
            EmailTables.SUPPRESSION,
            new com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter());
      H2Support.insert(
            EmailTables.SUPPRESSION,
            new QRecord()
                  .withValue("address", "person@example.com")
                  .withValue("scope", "NOTIFICATION")
                  .withValue("reason", "UNSUBSCRIBE"));
      assertThat(EmailService.send(request()).status()).isEqualTo(EmailSendResult.Status.QUEUED);
      assertThat(EmailService.send(request().withCategory(EmailCategory.NOTIFICATION)).status())
            .isEqualTo(EmailSendResult.Status.SUPPRESSED);
      SystemWrites.updateOne(
            EmailTables.SUPPRESSION, new QRecord().withValue("id", 2).withValue("scope", "ALL"));
      assertThat(EmailService.send(request().withCategory(EmailCategory.SECURITY)).status())
            .isEqualTo(EmailSendResult.Status.QUEUED);
      SystemWrites.updateOne(
            EmailTables.SUPPRESSION,
            new QRecord().withValue("id", 2).withValue("expiresDate", now.minusSeconds(1)));
      assertThat(EmailService.send(request().withCategory(EmailCategory.MARKETING)).status())
            .isEqualTo(EmailSendResult.Status.QUEUED);
   }

   /*******************************************************************************
    ** Rate Limits Do Not Consume Attempts And Send After Is Honored.
    *******************************************************************************/
   @Test
   void rateLimitsDoNotConsumeAttemptsAndSendAfterIsHonored() throws Exception
   {
      SystemWrites.updateOne(
            EmailTables.PROVIDER,
            new QRecord().withValue("id", 1).withValue("maxPerMinute", 1).withValue("maxPerDay", 2));
      EmailService.send(request());
      EmailService.send(request());
      var deferred = EmailService.send(request().withSendAfter(now.plusSeconds(1000)));
      var dispatcher = fake((e, s) -> new EmailTransportResult("ok"));
      assertThat(dispatcher.runOnce().sent()).isEqualTo(1);
      assertThat(H2Support.all(EmailTables.ATTEMPT)).hasSize(1);
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(61), ZoneOffset.UTC));
      assertThat(dispatcher.runOnce().sent()).isEqualTo(1);
      assertThat(dispatcher.sendNow(deferred.messageId()))
            .isEqualTo(EmailDispatcher.SendResult.NOT_SENT);
   }

   /*******************************************************************************
    ** Transaction Commit Rollback And Cross Backend Callback.
    *******************************************************************************/
   @Test
   void transactionCommitRollbackAndCrossBackendCallback() throws Exception
   {
      try(var tx = QBackendTransaction.openFor(new InsertInput(EmailTables.MESSAGE)))
      {
         assertThat(EmailService.send(request().withTransaction(tx, "rdbms")).status())
               .isEqualTo(EmailSendResult.Status.QUEUED);
         tx.rollback();
      }
      assertThat(H2Support.all(EmailTables.MESSAGE)).isEmpty();
      try(var tx = QBackendTransaction.openFor(new InsertInput(EmailTables.MESSAGE)))
      {
         EmailService.send(request().withTransaction(tx, "rdbms"));
         tx.commit();
      }
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
      var other = new QBackendTransaction();
      var result = EmailService.send(request().withTransaction(other, "elsewhere"));
      assertThat(result.messageId()).isNull();
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
      other.commit();
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(2);
      var rolledBack = new QBackendTransaction();
      EmailService.send(request().withTransaction(rolledBack, "elsewhere"));
      rolledBack.rollback();
      rolledBack.commit();
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(2);
   }

   /*******************************************************************************
    ** Bridge Core Action Queues And Delivers.
    *******************************************************************************/
   @Test
   void bridgeCoreActionQueuesAndDelivers() throws Exception
   {
      new SendMessageAction()
            .execute(
                  new SendMessageInput()
                        .withMessagingProviderName("qbitEmail")
                        .withSubject("Bridge")
                        .withTo(
                              new MultiParty()
                                    .withParty(
                                          new Party()
                                                .withAddress("person@example.com")
                                                .withRole(EmailPartyRole.TO))
                                    .withParty(
                                          new Party()
                                                .withAddress("copy@example.com")
                                                .withRole(EmailPartyRole.CC)))
                        .withFrom(new Party().withAddress("sender@example.com").withLabel("Sender"))
                        .withContent(
                              new Content().withContentRole(EmailContentRole.HTML).withBody("<p>Bridge</p>"))
                        .withContent(
                              new Content().withContentRole(EmailContentRole.TEXT).withBody("Bridge")));
      assertThat(H2Support.all(EmailTables.MESSAGE))
            .hasSize(1)
            .allMatch(r -> "QUEUED".equals(r.getValueString("status")));
      assertThat(mail.getReceivedMessages()).isEmpty();
      assertThat(new EmailDispatcher().runOnce().sent()).isEqualTo(1);
      assertThat(mail.waitForIncomingEmail(2000, 2)).isTrue();
   }

   /*******************************************************************************
    ** Provider Health Ten Failures Probe And Recovery.
    *******************************************************************************/
   @Test
   void providerHealthTenFailuresProbeAndRecovery() throws Exception
   {
      EmailRuntime.config().withMaxAttempts(20);
      for(int i = 0; i < 11; i++)
      {
         EmailService.send(request());
      }
      var dispatcher =
            fake(
                  (e, s) ->
                  {
                     throw new EmailTransportException("temporary", false);
                  });
      assertThat(dispatcher.runOnce().failed()).isEqualTo(10);
      assertThat(H2Support.all(EmailTables.PROVIDER).getFirst().getValueString("healthStatus"))
            .isEqualTo("UNHEALTHY");
      assertThat(dispatcher.runOnce().failed()).isZero();
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(301), ZoneOffset.UTC));
      assertThat(dispatcher.maintain().failed()).isEqualTo(1);
      EmailRuntime.setClock(Clock.fixed(now.plusSeconds(602), ZoneOffset.UTC));
      assertThat(fake((e, s) -> new EmailTransportResult("ok")).runOnce().sent()).isGreaterThan(0);
      assertThat(H2Support.all(EmailTables.PROVIDER).getFirst().getValueString("healthStatus"))
            .isEqualTo("HEALTHY");
   }

   /*******************************************************************************
    ** Retention Keeps Digest And Unfinished Messages.
    *******************************************************************************/
   @Test
   void retentionKeepsDigestAndUnfinishedMessages() throws Exception
   {
      var sent = EmailService.send(request());
      fake((e, s) -> new EmailTransportResult("ok")).sendNow(sent.messageId());
      var waiting = EmailService.send(request());
      EmailRuntime.setClock(Clock.fixed(now.plus(Duration.ofDays(31)), ZoneOffset.UTC));
      EmailProcesses.retain();
      assertThat(message(sent.messageId()).getValueString("htmlBody")).isNull();
      assertThat(message(sent.messageId()).getValueString("bodySha256")).hasSize(64);
      assertThat(message(waiting.messageId()).getValueString("htmlBody")).isNotNull();
      EmailRuntime.config().withMessageRetentionDays(30);
      EmailProcesses.retain();
      assertThat(message(sent.messageId())).isNull();
      assertThat(H2Support.all(EmailTables.ATTEMPT)).isEmpty();
   }

   /*******************************************************************************
    ** Two Replicas Claim Each Message Once And Share Rate Budget.
    *******************************************************************************/
   @Test
   void twoReplicasClaimEachMessageOnceAndShareRateBudget() throws Exception
   {
      var instanceA = QContext.getQInstance();
      var backend =
            (com.kingsrook.qqq.backend.module.rdbms.model.metadata.RDBMSBackendMetaData)
                  instanceA.getBackend("rdbms");
      var instanceB = H2Support.instance(backend, c ->
      {
      });
      for(int i = 0; i < 25; i++)
      {
         EmailService.send(request());
      }
      var received = new ConcurrentHashMap<String, AtomicInteger>();
      var ready = new CyclicBarrier(2);
      try(var pool = Executors.newFixedThreadPool(2))
      {
         List<Future<?>> futures = new ArrayList<>();
         for(var instance : List.of(instanceA, instanceB))
         {
            futures.add(
                  pool.submit(
                        () ->
                        {
                           QContext.init(instance, H2Support.admin());
                           try
                           {
                              ready.await();
                              var worker =
                                    fake(
                                          (e, s) ->
                                          {
                                             received
                                                   .computeIfAbsent(e.getMessageId(), k -> new AtomicInteger())
                                                   .incrementAndGet();
                                             return (new EmailTransportResult("ok"));
                                          });
                              for(int i = 0; i < 3; i++)
                              {
                                 worker.runOnce();
                              }
                           }
                           catch(Exception e)
                           {
                              throw new RuntimeException(e);
                           }
                           finally
                           {
                              QContext.clear();
                           }
                        }));
         }
         for(var future : futures)
         {
            future.get(30, TimeUnit.SECONDS);
         }
      }
      assertThat(received).hasSize(25);
      assertThat(received.values()).allMatch(n -> n.get() == 1);
      assertThat(H2Support.all(EmailTables.ATTEMPT)).hasSize(25);
   }

   /*******************************************************************************
    ** Concurrent Idempotency Joins Caller Transactions.
    *******************************************************************************/
   @Test
   void concurrentIdempotencyJoinsCallerTransactions() throws Exception
   {
      var instance = QContext.getQInstance();
      try(var pool = Executors.newFixedThreadPool(4))
      {
         var barrier = new CyclicBarrier(4);
         List<Future<EmailSendResult>> results = new ArrayList<>();
         for(int i = 0; i < 4; i++)
         {
            results.add(
                  pool.submit(
                        () ->
                        {
                           QContext.init(instance, H2Support.admin());
                           try(var tx = QBackendTransaction.openFor(new InsertInput(EmailTables.MESSAGE)))
                           {
                              barrier.await();
                              var result =
                                    EmailService.send(
                                          request()
                                                .withIdempotencyKey("concurrent")
                                                .withTransaction(tx, "rdbms"));
                              tx.commit();
                              return (result);
                           }
                           finally
                           {
                              QContext.clear();
                           }
                        }));
         }
         List<EmailSendResult> outcomes = new ArrayList<>();
         for(var f : results)
         {
            outcomes.add(f.get(20, TimeUnit.SECONDS));
         }
         assertThat(outcomes).filteredOn(r -> r.status() == EmailSendResult.Status.QUEUED).hasSize(1);
         assertThat(outcomes)
               .filteredOn(r -> r.status() == EmailSendResult.Status.DUPLICATE)
               .hasSize(3);
         assertThat(outcomes.stream().map(EmailSendResult::messageId).distinct()).hasSize(1);
      }
      assertThat(H2Support.all(EmailTables.MESSAGE)).hasSize(1);
   }

   /*******************************************************************************
    ** Replicas Share Rate Limit And Limited Provider Does Not Starve Others.
    *******************************************************************************/
   @Test
   void replicasShareRateLimitAndLimitedProviderDoesNotStarveOthers() throws Exception
   {
      EmailRuntime.config().withBatchSize(1);
      SystemWrites.updateOne(
            EmailTables.PROVIDER, new QRecord().withValue("id", 1).withValue("maxPerMinute", 1));
      var other =
            H2Support.insert(
                  EmailTables.PROVIDER,
                  new QRecord()
                        .withValue("name", "other")
                        .withValue("kind", "SMTP")
                        .withValue("host", "127.0.0.1")
                        .withValue("port", 25)
                        .withValue("fromAddress", "sender@example.com")
                        .withValue("isDefault", false)
                        .withValue("enabled", true));
      for(int i = 0; i < 3; i++)
      {
         EmailService.send(request());
      }
      var otherMessage = EmailService.send(request().withProviderName("other"));
      var instance = QContext.getQInstance();
      try(var pool = Executors.newFixedThreadPool(2))
      {
         List<Future<?>> futures = new ArrayList<>();
         for(int i = 0; i < 2; i++)
         {
            futures.add(
                  pool.submit(
                        () ->
                        {
                           QContext.init(instance, H2Support.admin());
                           try
                           {
                              fake((e, settings) -> new EmailTransportResult("ok")).runOnce();
                           }
                           catch(Exception e)
                           {
                              throw new RuntimeException(e);
                           }
                           finally
                           {
                              QContext.clear();
                           }
                        }));
         }
         for(var f : futures)
         {
            f.get(20, TimeUnit.SECONDS);
         }
      }
      assertThat(H2Support.all(EmailTables.ATTEMPT)).hasSize(2);
      assertThat(message(otherMessage.messageId()).getValueString("status")).isEqualTo("SENT");
   }

   /*******************************************************************************
    ** Maintenance Recovers Disabled Provider Lease And Exhausted Attempts.
    *******************************************************************************/
   @Test
   void maintenanceRecoversDisabledProviderLeaseAndExhaustedAttempts() throws Exception
   {
      EmailRuntime.config().withMaxAttempts(1);
      var queued = EmailService.send(request());
      var inFlight =
            fake(
                  (e, settings) ->
                  {
                     try
                     {
                        SystemWrites.updateOne(
                              EmailTables.PROVIDER,
                              new QRecord().withValue("id", 1).withValue("enabled", false));
                        EmailRuntime.setClock(Clock.fixed(now.plusSeconds(121), ZoneOffset.UTC));
                        new EmailDispatcher().maintain();
                     }
                     catch(Exception ex)
                     {
                        throw new RuntimeException(ex);
                     }
                     return (new EmailTransportResult("too-late"));
                  });
      assertThat(inFlight.sendNow(queued.messageId())).isEqualTo(EmailDispatcher.SendResult.NOT_SENT);
      assertThat(message(queued.messageId()).getValueString("status")).isEqualTo("DEAD_LETTER");
      assertThat(message(queued.messageId()).getValueInstant("finishedDate"))
            .isEqualTo(now.plusSeconds(121));
      assertThat(H2Support.all(EmailTables.ATTEMPT).getFirst().getValueString("outcome"))
            .isEqualTo("LEASE_EXPIRED");
   }
}
