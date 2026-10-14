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
import java.util.List;
import java.util.Locale;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.update.UpdateInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import jakarta.mail.internet.InternetAddress;


/*******************************************************************************
 ** DB-only receipt, event deduplication and suppression writes.
 *******************************************************************************/
public final class EmailTrackingStore
{
   /*******************************************************************************
    ** Distinguish an authenticated retry from reuse of a Mailgun nonce.
    *******************************************************************************/
   public enum Receipt
   {
      RECEIVED, DUPLICATE, REPLAY
   }

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailTrackingStore()
   {
   }

   /*******************************************************************************
    ** Serialize address upserts across providers, then acquire the dispatcher lock.
    ** Every tracking writer uses this order and does no network I/O while locked.
    *******************************************************************************/
   public static QRecord lock(Integer providerId, QBackendTransaction tx) throws QException
   {
      List<QRecord> providers = SystemWrites.query(EmailTables.PROVIDER, new QQueryFilter().withOrderBy(new QFilterOrderBy("id")).withLimit(1), tx);
      if(providers.isEmpty())
      {
         throw (new QException("Email provider unavailable"));
      }
      EmailStore.lockProvider(providers.getFirst().getValueInteger("id"), tx);
      QRecord provider = EmailStore.lockProvider(providerId, tx);
      if(provider == null)
      {
         throw (new QException("Email provider unavailable"));
      }
      return (provider);
   }

   /*******************************************************************************
    ** A provider lock avoids expected duplicate SQL errors (and payload logging).
    ** Unique constraints remain the final database guard.
    *******************************************************************************/
   public static Receipt receive(QRecord provider, String externalId, String nonce, byte[] bytes, String note) throws QException
   {
      Integer providerId = provider.getValueInteger("id");
      try(QBackendTransaction tx = QBackendTransaction.openFor(new UpdateInput(EmailTables.PROVIDER)))
      {
         EmailStore.lockProvider(providerId, tx);
         QQueryFilter filter = EmailStore.equal("providerId", providerId);
         if(nonce != null && SystemWrites.count(EmailTables.INBOX, filter.clone().withCriteria("nonce", QCriteriaOperator.EQUALS, nonce), tx) > 0)
         {
            return (Receipt.REPLAY);
         }
         if(SystemWrites.count(EmailTables.INBOX, filter.withCriteria("externalEventId", QCriteriaOperator.EQUALS, externalId), tx) > 0)
         {
            return (Receipt.DUPLICATE);
         }
         var now = EmailRuntime.clock().instant();
         QRecord row = new QRecord().withValue("providerId", providerId).withValue("kind", provider.getValue("kind"))
            .withValue("externalEventId", externalId).withValue("nonce", nonce).withValue("status", note == null ? "PENDING" : "IGNORED")
            .withValue("attempts", 0).withValue("nextAttemptDate", note == null ? now : null).withValue("lastError", note)
            .withValue("payloadJson", new String(bytes, StandardCharsets.UTF_8)).withValue("payloadSha256", WebhookVerification.hash(bytes))
            .withValue("receivedDate", now).withValue("processedDate", note == null ? null : now);
         copySecurity(provider, row);
         insert(EmailTables.INBOX, row, tx);
         tx.commit();
         return (Receipt.RECEIVED);
      }
      catch(QException e)
      {
         // Re-query after rollback: a unique violation aborts a PostgreSQL transaction.
         if(nonce != null && SystemWrites.count(EmailTables.INBOX, EmailStore.equal("providerId", providerId)
            .withCriteria("nonce", QCriteriaOperator.EQUALS, nonce)) > 0)
         {
            return (Receipt.REPLAY);
         }
         if(SystemWrites.count(EmailTables.INBOX, EmailStore.equal("providerId", providerId)
            .withCriteria("externalEventId", QCriteriaOperator.EQUALS, externalId)) > 0)
         {
            return (Receipt.DUPLICATE);
         }
         throw (new QException("Email inbox write failed"));
      }
   }

   /*******************************************************************************
    ** Fail atomically without exposing input values or database diagnostics.
    *******************************************************************************/
   public static QRecord insert(String table, QRecord row, QBackendTransaction tx) throws QException
   {
      List<QRecord> records = SystemWrites.insert(table, List.of(row), tx).getRecords();
      if(records.isEmpty() || records.getFirst().getValueInteger("id") == null || (records.getFirst().getErrors() != null && !records.getFirst().getErrors().isEmpty()))
      {
         throw (new QException("Email tracking write failed"));
      }
      return (records.getFirst());
   }

   /*******************************************************************************
    ** Preserve host tenant security on child and suppression records.
    *******************************************************************************/
   public static void copySecurity(QRecord source, QRecord target)
   {
      for(var field : EmailRuntime.config().getSecurityFields())
      {
         target.withValue(field.getName(), source.getValue(field.getName()));
      }
   }

   /*******************************************************************************
    ** Insert a recipient event once even across overlapping provider batches.
    *******************************************************************************/
   public static QRecord event(QRecord message, EmailEventParser.Event event, String key, QBackendTransaction tx) throws QException
   {
      if(SystemWrites.count(EmailTables.EVENT, EmailStore.equal("eventKey", key), tx) > 0)
      {
         return (null);
      }
      QRecord row = new QRecord().withValue("messageId", message.getValue("id")).withValue("providerId", message.getValue("providerId"))
         .withValue("eventType", event.type()).withValue("bounceType", event.bounceType()).withValue("recipient", address(event.recipient()))
         .withValue("occurredDate", event.occurred()).withValue("detail", event.detail()).withValue("url", "CLICKED".equals(event.type()) ? event.url() : null)
         .withValue("eventKey", key);
      copySecurity(message, row);
      return (insert(EmailTables.EVENT, row, tx));
   }

   /*******************************************************************************
    ** Upsert without weakening a complaint or shortening an existing suppression.
    ** All callers hold the common tracking lock throughout their transaction.
    *******************************************************************************/
   public static void suppress(QRecord message, QRecord event, String scope, String reason, QBackendTransaction tx) throws QException
   {
      if("UNSUBSCRIBE".equals(reason) && !List.of("MARKETING", "NOTIFICATION").contains(scope == null ? "" : scope))
      {
         return;
      }
      String address = event.getValueString("recipient");
      List<QRecord> existing = SystemWrites.query(EmailTables.SUPPRESSION, EmailStore.equal("address", address)
         .withCriteria("scope", QCriteriaOperator.EQUALS, scope), tx);
      QRecord row = new QRecord().withValue("address", address).withValue("scope", scope).withValue("reason", reason)
         .withValue("sourceMessageId", message.getValue("id")).withValue("sourceEventId", event.getValue("id")).withValue("expiresDate", null);
      copySecurity(message, row);
      if(existing.isEmpty())
      {
         row.withValue("createdDate", EmailRuntime.clock().instant());
         insert(EmailTables.SUPPRESSION, row, tx);
      }
      else
      {
         QRecord old = existing.getFirst();
         if("COMPLAINT".equals(old.getValueString("reason")))
         {
            row.withValue("reason", "COMPLAINT");
         }
         row.withValue("id", old.getValue("id"));
         EmailStore.update(EmailTables.SUPPRESSION, row, tx);
      }
   }

   /*******************************************************************************
    ** Canonical mailbox for event deduplication and suppression lookup.
    *******************************************************************************/
   public static String address(String value)
   {
      try
      {
         InternetAddress address = new InternetAddress(value, true);
         address.validate();
         return (address.getAddress().toLowerCase(Locale.ROOT));
      }
      catch(Exception e)
      {
         throw (new IllegalArgumentException("Email event recipient is invalid"));
      }
   }
}
