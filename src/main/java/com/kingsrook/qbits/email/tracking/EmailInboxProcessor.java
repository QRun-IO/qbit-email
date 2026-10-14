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


import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.delivery.EmailAlerts;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;


/*******************************************************************************
 ** Bounded, replica-safe database transactions apply inbox rows exactly once.
 ** No network or external effects occur while the row's provider is locked.
 *******************************************************************************/
public final class EmailInboxProcessor
{
   /*******************************************************************************
    ** Poll due rows; transaction rollback releases claims after a worker crash.
    *******************************************************************************/
   public int runOnce() throws QException
   {
      int processed = 0;
      for(QRecord row : SystemWrites.query(EmailTables.INBOX, EmailStore.equal("status", "PENDING")
         .withCriteria("nextAttemptDate", QCriteriaOperator.LESS_THAN_OR_EQUALS, EmailRuntime.clock().instant())
         .withOrderBy(new QFilterOrderBy("id")).withLimit(EmailRuntime.config().getBatchSize())))
      {
         if(process(row))
         {
            processed++;
         }
      }
      return (processed);
   }

   /*******************************************************************************
    ** Re-read eligibility after the lock, including the observed attempt number.
    *******************************************************************************/
   private static boolean due(QRecord current, QRecord observed)
   {
      return (current != null && "PENDING".equals(current.getValueString("status"))
         && Objects.equals(current.getValue("attempts"), observed.getValue("attempts"))
         && current.getValueInstant("nextAttemptDate") != null && !current.getValueInstant("nextAttemptDate").isAfter(EmailRuntime.clock().instant()));
   }

   /*******************************************************************************
    ** All event, message, suppression and receipt changes commit together.
    *******************************************************************************/
   private boolean process(QRecord observed) throws QException
   {
      boolean complaint = false;
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
      {
         QRecord provider = EmailTrackingStore.lock(observed.getValueInteger("providerId"), tx);
         QRecord row = EmailStore.get(EmailTables.INBOX, observed.getValueInteger("id"), tx);
         if(!due(row, observed))
         {
            return (false);
         }
         int applied = 0;
         for(EmailEventParser.Event event : new EmailEventParser().parse(row.getValueString("kind"), row.getValueString("payloadJson"), row.getValueInstant("receivedDate")))
         {
            if(("OPENED".equals(event.type()) && !Boolean.TRUE.equals(provider.getValueBoolean("trackOpens")))
               || ("CLICKED".equals(event.type()) && !Boolean.TRUE.equals(provider.getValueBoolean("trackClicks"))))
            {
               continue;
            }
            QRecord message = match(event, provider.getValueInteger("id"), tx);
            if(message == null || "SENDING".equals(message.getValueString("status")))
            {
               throw (new QException("Email event is awaiting its message"));
            }
            if(Boolean.TRUE.equals(message.getValueBoolean("phiApproved")) && List.of("OPENED", "CLICKED").contains(event.type()))
            {
               continue;
            }
            String key = provider.getValueInteger("id") + ":" + WebhookVerification.hash((event.externalId() + "\n" + event.type() + "\n"
               + EmailTrackingStore.address(event.recipient())).getBytes(StandardCharsets.UTF_8));
            QRecord stored = EmailTrackingStore.event(message, event, key, tx);
            if(stored == null)
            {
               continue;
            }
            applied++;
            updateMessage(message, stored, tx);
            if("COMPLAINED".equals(event.type()) || ("BOUNCED".equals(event.type()) && "HARD".equals(event.bounceType())))
            {
               EmailTrackingStore.suppress(message, stored, "ALL", "COMPLAINED".equals(event.type()) ? "COMPLAINT" : "HARD_BOUNCE", tx);
               complaint |= "COMPLAINED".equals(event.type());
            }
            else if("UNSUBSCRIBED".equals(event.type()))
            {
               EmailTrackingStore.suppress(message, stored, message.getValueString("category"), "UNSUBSCRIBE", tx);
            }
         }
         EmailStore.update(EmailTables.INBOX, new QRecord().withValue("id", row.getValue("id"))
            .withValue("status", applied == 0 ? "IGNORED" : "PROCESSED").withValue("attempts", row.getValueInteger("attempts") + 1)
            .withValue("processedDate", EmailRuntime.clock().instant()).withValue("nextAttemptDate", null).withValue("lastError", null), tx);
         tx.commit();
      }
      catch(Exception e)
      {
         fail(observed);
         return (false);
      }
      if(complaint)
      {
         complaintAlert();
      }
      return (true);
   }

   /*******************************************************************************
    ** Match our id first, then the provider id; neither may cross providers.
    *******************************************************************************/
   private static QRecord match(EmailEventParser.Event event, Integer providerId, QBackendTransaction tx) throws QException
   {
      if(event.messageId() != null && event.messageId().matches("[0-9]{1,10}") && Long.parseLong(event.messageId()) <= Integer.MAX_VALUE)
      {
         QRecord message = EmailStore.get(EmailTables.MESSAGE, Integer.valueOf(event.messageId()), tx);
         if(message != null && providerId.equals(message.getValueInteger("providerId")))
         {
            return (message);
         }
      }
      if(event.providerMessageId() == null || event.providerMessageId().isBlank())
      {
         return (null);
      }
      List<QRecord> rows = SystemWrites.query(EmailTables.MESSAGE, EmailStore.equal("providerId", providerId)
         .withCriteria("providerMessageId", QCriteriaOperator.EQUALS, event.providerMessageId()), tx);
      return (rows.size() == 1 ? rows.getFirst() : null);
   }

   /*******************************************************************************
    ** Complaint outranks hard bounce, which outranks delivery; never regress.
    *******************************************************************************/
   private static void updateMessage(QRecord message, QRecord event, QBackendTransaction tx) throws QException
   {
      String type = event.getValueString("eventType");
      String status = message.getValueString("status");
      String field = switch(type)
      {
         case "DELIVERED" -> "SENT".equals(status) ? "deliveredDate" : null;
         case "BOUNCED" -> "HARD".equals(event.getValueString("bounceType")) && List.of("SENT", "DELIVERED").contains(status) ? "bouncedDate" : null;
         case "COMPLAINED" -> List.of("SENT", "DELIVERED", "BOUNCED").contains(status) ? "complainedDate" : null;
         default -> null;
      };
      if(field != null)
      {
         EmailStore.update(EmailTables.MESSAGE, new QRecord().withValue("id", message.getValue("id")).withValue("status", type)
            .withValue(field, event.getValue("occurredDate")), tx);
      }
   }

   /*******************************************************************************
    ** After rollback, only the still-due observed attempt may consume retry budget.
    *******************************************************************************/
   private static void fail(QRecord observed) throws QException
   {
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
      {
         EmailTrackingStore.lock(observed.getValueInteger("providerId"), tx);
         QRecord row = EmailStore.get(EmailTables.INBOX, observed.getValueInteger("id"), tx);
         if(due(row, observed))
         {
            int attempts = row.getValueInteger("attempts") + 1;
            EmailStore.update(EmailTables.INBOX, new QRecord().withValue("id", row.getValue("id")).withValue("attempts", attempts)
               .withValue("status", attempts >= 6 ? "FAILED" : "PENDING").withValue("lastError", "Email event processing failed; check message matching and payload validity.")
               .withValue("nextAttemptDate", attempts >= 6 ? null : EmailRuntime.clock().instant().plusSeconds(60L * EmailRuntime.config().retryMinutesAfterAttempt(attempts))), tx);
            tx.commit();
         }
      }
   }

   /*******************************************************************************
    ** Standard Reprocess action rechecks FAILED under the same transaction lock.
    *******************************************************************************/
   public static boolean reprocess(Integer id) throws QException
   {
      QRecord selected = EmailStore.get(EmailTables.INBOX, id, null);
      if(selected == null)
      {
         return (false);
      }
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
      {
         EmailTrackingStore.lock(selected.getValueInteger("providerId"), tx);
         QRecord row = EmailStore.get(EmailTables.INBOX, id, tx);
         if(!"FAILED".equals(row.getValueString("status")))
         {
            return (false);
         }
         EmailStore.update(EmailTables.INBOX, new QRecord().withValue("id", id).withValue("status", "PENDING").withValue("attempts", 0)
            .withValue("nextAttemptDate", EmailRuntime.clock().instant()).withValue("lastError", null).withValue("processedDate", null), tx);
         tx.commit();
         return (true);
      }
   }

   /*******************************************************************************
    ** Optional alerting cannot turn a committed event into a failed inbox row.
    *******************************************************************************/
   private static void complaintAlert()
   {
      try
      {
         Class.forName("com.kingsrook.qqq.backend.core.alerts.QAlerts");
         if(SystemWrites.count(EmailTables.EVENT, EmailStore.equal("eventType", "COMPLAINED")
            .withCriteria("occurredDate", QCriteriaOperator.GREATER_THAN_OR_EQUALS, EmailRuntime.clock().instant().minusSeconds(86400))
            .withCriteria("occurredDate", QCriteriaOperator.LESS_THAN_OR_EQUALS, EmailRuntime.clock().instant())) > 3)
         {
            EmailAlerts.raise(EmailAlerts.COMPLAINT, 0);
         }
      }
      catch(Exception | LinkageError e)
      {
         // Alert integration is optional; event data is already committed.
      }
   }
}
