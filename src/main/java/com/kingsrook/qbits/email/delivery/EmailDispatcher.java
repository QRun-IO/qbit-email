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

package com.kingsrook.qbits.email.delivery;


import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.transport.EmailEnvelope;
import com.kingsrook.qbits.email.transport.EmailProviderSettings;
import com.kingsrook.qbits.email.transport.EmailTransportException;
import com.kingsrook.qbits.email.transport.EmailTransportRegistry;
import com.kingsrook.qbits.email.transport.EmailTransportResult;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.logging.QLogger;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import static com.kingsrook.qqq.backend.core.logging.LogUtils.logPair;


/*******************************************************************************
 ** Durable at-least-once delivery with unique attempt claims and database leases.
 *******************************************************************************/
public class EmailDispatcher
{
   private static final QLogger LOG = QLogger.getLogger(EmailDispatcher.class);

   public static final List<String> DUE = List.of("QUEUED", "RETRY_WAIT");
   public static final List<String> FINAL = List.of("SENT", "DELIVERED", "DEAD_LETTER", "BOUNCED", "COMPLAINED", "SUPPRESSED", "CANCELLED");
   private final EmailTransportRegistry transports;
   private final String runner = EmailRuntime.nodeId() + "/" + UUID.randomUUID();

   /*******************************************************************************
    ** Default delivery transports.
    *******************************************************************************/
   public EmailDispatcher()
   {
      this(new EmailTransportRegistry());
   }

   /*******************************************************************************
    ** Inject transport registry for host extensions and tests.
    *******************************************************************************/
   public EmailDispatcher(EmailTransportRegistry transports)
   {
      this.transports = transports;
   }

   /*******************************************************************************
    ** One message delivery result.
    *******************************************************************************/
   public enum SendResult
   {
      SENT,
      RETRY_WAIT,
      DEAD_LETTER,
      PROVIDER_FAILURE,
      NOT_SENT
   }

   /*******************************************************************************
    ** Counts from a dispatch pass.
    *******************************************************************************/
   public record RunSummary(int sent, int failed, int skipped)
   {
   }

   /*******************************************************************************
    ** A database claim, fully committed before network I/O.
    *******************************************************************************/
   private record Claim(QRecord message, QRecord provider, QRecord attempt)
   {
   }

   /*******************************************************************************
    ** Dispatch the configured batch.
    *******************************************************************************/
   public RunSummary runOnce() throws QException
   {
      return (run(null));
   }

   /*******************************************************************************
    ** Dispatch due message ids, or all due messages when null.
    *******************************************************************************/
   public RunSummary run(List<Integer> ids) throws QException
   {
      if(EmailRuntime.config() == null || (ids != null && ids.isEmpty()))
      {
         return (new RunSummary(0, 0, 0));
      }
      QQueryFilter filter = new QQueryFilter(new QFilterCriteria("status", QCriteriaOperator.IN, "QUEUED", "RETRY_WAIT", "SENDING"))
         .withCriteria(new QFilterCriteria("nextAttemptDate", QCriteriaOperator.LESS_THAN_OR_EQUALS, EmailRuntime.clock().instant()))
         .withOrderBy(new QFilterOrderBy("id")).withLimit(EmailRuntime.config().getBatchSize());
      if(ids != null)
      {
         filter.withCriteria(new QFilterCriteria("id", QCriteriaOperator.IN, new ArrayList<>(ids)));
      }
      int sent = 0;
      int failed = 0;
      int skipped = 0;
      Integer afterId = 0;
      Set<Integer> failedProviders = new HashSet<>();
      while(sent + failed < EmailRuntime.config().getBatchSize())
      {
         QQueryFilter pageFilter = filter.clone().withCriteria(new QFilterCriteria("id", QCriteriaOperator.GREATER_THAN, afterId));
         List<QRecord> page = SystemWrites.query(EmailTables.MESSAGE, pageFilter);
         if(page.isEmpty())
         {
            break;
         }
         for(QRecord row : page)
         {
            afterId = row.getValueInteger("id");
            if(failedProviders.contains(row.getValueInteger("providerId")))
            {
               skipped++;
               continue;
            }
            try
            {
               SendResult result = attempt(afterId, false);
               switch(result)
               {
                  case SENT -> sent++;
                  case RETRY_WAIT, DEAD_LETTER -> failed++;
                  case PROVIDER_FAILURE ->
                  {
                     failed++;
                     failedProviders.add(row.getValueInteger("providerId"));
                  }
                  default -> skipped++;
               }
            }
            catch(QException | RuntimeException e)
            {
               failed++;
               LOG.warn("Email attempt failed", logPair("messageId", afterId));
            }
            if(sent + failed >= EmailRuntime.config().getBatchSize())
            {
               break;
            }
         }
      }
      return (new RunSummary(sent, failed, skipped));
   }

   /*******************************************************************************
    ** Manual send bypasses due time but preserves rate limits, health and leases.
    *******************************************************************************/
   public SendResult sendNow(Integer id) throws QException
   {
      SendResult result = attempt(id, true);
      return (result == SendResult.PROVIDER_FAILURE ? SendResult.RETRY_WAIT : result);
   }

   /*******************************************************************************
    ** Claim, send outside the transaction, then persist the fenced result.
    *******************************************************************************/
   protected SendResult attempt(Integer id, boolean now) throws QException
   {
      Claim claim = claim(id, now);
      if(claim == null)
      {
         return (SendResult.NOT_SENT);
      }
      EmailTransportResult result = null;
      EmailTransportException error = null;
      try
      {
         if(Boolean.TRUE.equals(claim.message().getValueBoolean("phiApproved")))
         {
            com.kingsrook.qbits.email.template.EmailTemplateRenderer.checkProvider(claim.provider());
         }
         result = transports.get(claim.provider().getValueString("kind")).send(envelope(claim.message()), new EmailProviderSettings(claim.provider(), EmailRuntime.config()));
      }
      catch(EmailTransportException e)
      {
         error = e;
      }
      catch(RuntimeException e)
      {
         error = new EmailTransportException("Transport temporarily unavailable", false);
      }
      return (finish(claim, result, error));
   }

   /*******************************************************************************
    ** Unique attempt insertion under a short provider lock, including rate checks.
    *******************************************************************************/
   private Claim claim(Integer id, boolean immediate) throws QException
   {
      EmailQBitConfig config = EmailRuntime.config();
      QRecord initial = EmailStore.get(EmailTables.MESSAGE, id, null);
      if(initial == null || config == null)
      {
         return (null);
      }
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
      {
         QRecord provider = EmailStore.lockProvider(initial.getValueInteger("providerId"), tx);
         QRecord message = EmailStore.get(EmailTables.MESSAGE, id, tx);
         Instant time = EmailRuntime.clock().instant();
         if(provider == null || message == null || !Boolean.TRUE.equals(provider.getValueBoolean("enabled")))
         {
            return (null);
         }
         String status = message.getValueString("status");
         int previous = message.getValueInteger("attemptCount") == null ? 0 : message.getValueInteger("attemptCount");
         if("SENDING".equals(status))
         {
            Instant lease = message.getValueInstant("leaseExpiresDate");
            if(lease != null && lease.isAfter(time))
            {
               return (null);
            }
            expire(message, tx, time);
            if(budgetAttempts(id, previous, tx) >= config.getMaxAttempts())
            {
               EmailStore.update(EmailTables.MESSAGE, new QRecord().withValue("id", id).withValue("status", "DEAD_LETTER")
                  .withValue("deadLetterDate", time).withValue("finishedDate", time).withValue("leaseExpiresDate", null).withValue("nextAttemptDate", null).withValue("lastError", "Attempt lease expired"), tx);
               tx.commit();
               alert(() -> EmailAlerts.raise(EmailAlerts.DEAD_LETTER, provider.getValueInteger("id")));
               return (null);
            }
         }
         else if(!DUE.contains(status))
         {
            return (null);
         }
         if(!immediate && ((message.getValueInstant("sendAfter") != null && message.getValueInstant("sendAfter").isAfter(time))
            || (message.getValueInstant("nextAttemptDate") != null && message.getValueInstant("nextAttemptDate").isAfter(time))))
         {
            return (null);
         }
         if(limited(provider, tx, time))
         {
            return (null);
         }
         Instant lease = time.plusSeconds(config.getLeaseSeconds());
         QRecord attempt = new QRecord().withValue("messageId", id).withValue("providerId", provider.getValue("id"))
            .withValue("attemptNumber", previous + 1).withValue("outcome", "IN_FLIGHT").withValue("claimedBy", runner)
            .withValue("startDate", time).withValue("leaseExpiresDate", lease);
         for(var field : config.getSecurityFields())
         {
            attempt.setValue(field.getName(), message.getValue(field.getName()));
         }
         QRecord inserted = SystemWrites.insert(EmailTables.ATTEMPT, List.of(attempt), tx).getRecords().get(0);
         if(inserted.getValueInteger("id") == null || (inserted.getErrors() != null && !inserted.getErrors().isEmpty()))
         {
            return (null);
         }
         EmailStore.update(EmailTables.MESSAGE, new QRecord().withValue("id", id).withValue("status", "SENDING").withValue("attemptCount", previous + 1)
            .withValue("leaseExpiresDate", lease).withValue("nextAttemptDate", lease), tx);
         tx.commit();
         return (new Claim(message, provider, inserted));
      }
   }

   /*******************************************************************************
    ** Shared rate and unhealthy-provider probe budgets, counted from attempts.
    *******************************************************************************/
   private static boolean limited(QRecord provider, QBackendTransaction transaction, Instant now) throws QException
   {
      for(String field : List.of("maxPerMinute", "maxPerDay"))
      {
         Integer limit = provider.getValueInteger(field);
         Instant after = now.minusSeconds("maxPerMinute".equals(field) ? 60 : 86400);
         if(limit != null && SystemWrites.count(EmailTables.ATTEMPT, EmailStore.equal("providerId", provider.getValueInteger("id"))
            .withCriteria(new QFilterCriteria("startDate", QCriteriaOperator.GREATER_THAN, after)), transaction) >= limit)
         {
            return (true);
         }
      }
      if("UNHEALTHY".equals(provider.getValueString("healthStatus")))
      {
         return (SystemWrites.count(EmailTables.ATTEMPT, EmailStore.equal("providerId", provider.getValueInteger("id"))
            .withCriteria(new QFilterCriteria("startDate", QCriteriaOperator.GREATER_THAN, now.minusSeconds(300))), transaction) > 0);
      }
      return (false);
   }

   /*******************************************************************************
    ** Mark the abandoned attempt before retrying.
    *******************************************************************************/
   private static void expire(QRecord message, QBackendTransaction tx, Instant now) throws QException
   {
      for(QRecord attempt : SystemWrites.query(EmailTables.ATTEMPT, EmailStore.equal("messageId", message.getValueInteger("id"))
         .withCriteria(new QFilterCriteria("outcome", QCriteriaOperator.EQUALS, "IN_FLIGHT")), tx))
      {
         EmailStore.update(EmailTables.ATTEMPT, new QRecord().withValue("id", attempt.getValue("id")).withValue("outcome", "LEASE_EXPIRED")
            .withValue("endDate", now).withValue("error", "Attempt lease expired"), tx);
      }
   }

   /*******************************************************************************
    ** Fence late completions by attempt number and persist outcome atomically.
    *******************************************************************************/
   private SendResult finish(Claim claim, EmailTransportResult result, EmailTransportException error) throws QException
   {
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
      {
         Integer providerId = claim.provider().getValueInteger("id");
         QRecord provider = EmailStore.lockProvider(providerId, tx);
         QRecord message = EmailStore.get(EmailTables.MESSAGE, claim.message().getValueInteger("id"), tx);
         int number = claim.attempt().getValueInteger("attemptNumber");
         if(message == null || !"SENDING".equals(message.getValueString("status")) || !Integer.valueOf(number).equals(message.getValueInteger("attemptCount")))
         {
            return (SendResult.NOT_SENT);
         }
         Instant now = EmailRuntime.clock().instant();
         boolean success = error == null && result != null;
         boolean permanent = error != null && error.getPermanent();
         boolean providerLevel = error != null && error.getProviderLevel();
         String safeError = error == null ? null : permanent ? "Provider rejected the message" : "Provider temporarily unavailable";
         if(providerLevel)
         {
            safeError = "Credentials missing or refused";
         }
         if(error != null && "Provider transport not available until phase (b)".equals(error.getMessage()))
         {
            safeError = "Provider transport not available until phase (b)";
         }
         EmailStore.update(EmailTables.ATTEMPT, new QRecord().withValue("id", claim.attempt().getValue("id"))
            .withValue("outcome", success ? "SUCCEEDED" : providerLevel ? "PROVIDER_FAILURE" : permanent ? "PERMANENT_FAILURE" : "TRANSIENT_FAILURE")
            .withValue("endDate", now).withValue("durationMillis", (int) Math.max(0, Duration.between(claim.attempt().getValueInstant("startDate"), now).toMillis()))
            .withValue("error", safeError), tx);
         QRecord update = new QRecord().withValue("id", message.getValue("id")).withValue("leaseExpiresDate", null).withValue("lastError", safeError);
         SendResult outcome;
         if(success)
         {
            outcome = SendResult.SENT;
            update.withValue("status", "SENT").withValue("sentDate", now).withValue("finishedDate", now).withValue("providerMessageId", result.providerMessageId()).withValue("nextAttemptDate", null);
            EmailStore.update(EmailTables.PROVIDER, new QRecord().withValue("id", providerId).withValue("healthStatus", "HEALTHY").withValue("lastSuccessDate", now), tx);
         }
         else
         {
            boolean dead = permanent || (!providerLevel && budgetAttempts(message.getValueInteger("id"), number, tx) >= EmailRuntime.config().getMaxAttempts());
            outcome = dead ? SendResult.DEAD_LETTER : SendResult.RETRY_WAIT;
            long seconds = 60L * EmailRuntime.config().retryMinutesAfterAttempt(number);
            if(error != null && error.getRetryAfter() != null)
            {
               seconds = Math.max(1, Math.min(3600, error.getRetryAfter().getSeconds()));
            }
            update.withValue("status", outcome.name()).withValue("deadLetterDate", dead ? now : null).withValue("finishedDate", dead ? now : null).withValue("nextAttemptDate", dead ? null : now.plusSeconds(seconds));
            QRecord health = new QRecord().withValue("id", providerId).withValue("lastFailureDate", now).withValue("lastFailureMessage", safeError);
            if(providerLevel || (!permanent && consecutiveFailures(providerId, tx) >= 10))
            {
               health.withValue("healthStatus", "UNHEALTHY");
            }
            EmailStore.update(EmailTables.PROVIDER, health, tx);
         }
         EmailStore.update(EmailTables.MESSAGE, update, tx);
         QRecord updatedProvider = EmailStore.get(EmailTables.PROVIDER, providerId, tx);
         tx.commit();
         if(success && "UNHEALTHY".equals(provider.getValueString("healthStatus")))
         {
            alert(() -> EmailAlerts.recovered(providerId));
         }
         if("UNHEALTHY".equals(updatedProvider.getValueString("healthStatus")) && !"UNHEALTHY".equals(provider.getValueString("healthStatus")))
         {
            alert(() -> EmailAlerts.raise(EmailAlerts.UNHEALTHY, providerId));
         }
         if(outcome == SendResult.DEAD_LETTER)
         {
            alert(() -> EmailAlerts.raise(EmailAlerts.DEAD_LETTER, providerId));
         }
         LOG.info("Email attempt completed", logPair("messageId", message.getValue("id")), logPair("status", outcome.name()));
         return (providerLevel ? SendResult.PROVIDER_FAILURE : outcome);
      }
   }

   /*******************************************************************************
    ** Keep claim numbering monotonic while excluding provider failures from limits.
    *******************************************************************************/
   private static long budgetAttempts(Integer messageId, int attempts, QBackendTransaction tx) throws QException
   {
      return (attempts - SystemWrites.count(EmailTables.ATTEMPT, EmailStore.equal("messageId", messageId)
         .withCriteria(new QFilterCriteria("outcome", QCriteriaOperator.EQUALS, "PROVIDER_FAILURE")), tx));
   }

   /*******************************************************************************
    ** Optional alerting must never change the result of a committed delivery.
    *******************************************************************************/
   private static void alert(Runnable notification)
   {
      try
      {
         notification.run();
      }
      catch(RuntimeException e)
      {
         LOG.warn("Email alert unavailable");
      }
   }

   /*******************************************************************************
    ** Consecutive completed transient failures; a success or permanent rejection breaks the streak.
    *******************************************************************************/
   private static int consecutiveFailures(Integer providerId, QBackendTransaction tx) throws QException
   {
      int failures = 0;
      for(QRecord attempt : SystemWrites.query(EmailTables.ATTEMPT, EmailStore.equal("providerId", providerId)
         .withCriteria(new QFilterCriteria("outcome", QCriteriaOperator.NOT_EQUALS, "IN_FLIGHT"))
         .withOrderBy(new QFilterOrderBy("endDate", false)).withOrderBy(new QFilterOrderBy("id", false)).withLimit(10), tx))
      {
         if(!"TRANSIENT_FAILURE".equals(attempt.getValueString("outcome")))
         {
            break;
         }
         failures++;
      }
      return (failures);
   }

   /*******************************************************************************
    ** The persisted alternatives are reused verbatim on each attempt.
    *******************************************************************************/
   private static EmailEnvelope envelope(QRecord message)
   {
      return (new EmailEnvelope().withMessageId(message.getValueString("id")).withFrom(message.getValueString("fromAddress"))
         .withTo(split(message.getValueString("toAddresses"))).withCc(split(message.getValueString("ccAddresses"))).withBcc(split(message.getValueString("bccAddresses")))
         .withReplyTo(message.getValueString("replyTo")).withSubject(content(message, "subject"))
         .withHtmlBody(content(message, "htmlBody"))
         .withTextBody(content(message, "textBody"))
         .withHeaders(message.getValueString("unsubscribeUrl") == null ? java.util.Map.of() : java.util.Map.of("List-Unsubscribe", "<" + message.getValueString("unsubscribeUrl") + ">", "List-Unsubscribe-Post", "List-Unsubscribe=One-Click")));
   }

   /*******************************************************************************
    ** Only policy-marked messages may invoke body decryption. A caller-supplied
    ** enc:v1 string in ad-hoc content is text, never a decryption capability.
    *******************************************************************************/
   private static String content(QRecord message, String field)
   {
      String value = message.getValueString(field);
      if(!Boolean.TRUE.equals(message.getValueBoolean("phiApproved")))
      {
         return (value);
      }
      if(value != null && !value.startsWith("enc:v1:"))
      {
         throw (new IllegalArgumentException("Protected email content must be encrypted"));
      }
      return (com.kingsrook.qbits.email.phi.EmailBodyEncryption.decrypt(value));
   }

   /*******************************************************************************
    ** Preserve quoted display names by treating the stored list as one RFC address list.
    *******************************************************************************/
   private static List<String> split(String value)
   {
      return (value == null || value.isBlank() ? List.of() : List.of(value));
   }

   /*******************************************************************************
    ** Lease recovery and five-minute provider probes share the normal claim path.
    *******************************************************************************/
   public RunSummary maintain() throws QException
   {
      Instant now = EmailRuntime.clock().instant();
      for(QRecord candidate : SystemWrites.query(EmailTables.MESSAGE, EmailStore.equal("status", "SENDING")
         .withCriteria(new QFilterCriteria("leaseExpiresDate", QCriteriaOperator.LESS_THAN_OR_EQUALS, now))))
      {
         try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
         {
            EmailStore.lockProvider(candidate.getValueInteger("providerId"), tx);
            QRecord current = EmailStore.get(EmailTables.MESSAGE, candidate.getValueInteger("id"), tx);
            if(current == null || !"SENDING".equals(current.getValueString("status")) || current.getValueInstant("leaseExpiresDate").isAfter(now))
            {
               continue;
            }
            expire(current, tx, now);
            boolean dead = budgetAttempts(current.getValueInteger("id"), current.getValueInteger("attemptCount"), tx) >= EmailRuntime.config().getMaxAttempts();
            EmailStore.update(EmailTables.MESSAGE, new QRecord().withValue("id", current.getValue("id"))
               .withValue("status", dead ? "DEAD_LETTER" : "RETRY_WAIT").withValue("nextAttemptDate", dead ? null : now)
               .withValue("leaseExpiresDate", null).withValue("lastError", "Attempt lease expired")
               .withValue("deadLetterDate", dead ? now : null).withValue("finishedDate", dead ? now : null), tx);
            tx.commit();
            if(dead)
            {
               alert(() -> EmailAlerts.raise(EmailAlerts.DEAD_LETTER, candidate.getValueInteger("providerId")));
            }
         }
      }
      return (runOnce());
   }
}
