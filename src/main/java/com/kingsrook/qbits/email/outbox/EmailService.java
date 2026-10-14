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

package com.kingsrook.qbits.email.outbox;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTableCustomizers;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.permissions.EmailPermissionChecker;
import com.kingsrook.qbits.email.permissions.EmailPermissions;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qqq.backend.core.actions.QBackendTransaction;
import com.kingsrook.qqq.backend.core.actions.permissions.CorePermissionEnforcer;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.messaging.SendMessageInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.insert.InsertInput;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import jakarta.mail.internet.InternetAddress;


/*******************************************************************************
 ** Validated transactional outbox API. This class never performs network delivery.
 *******************************************************************************/
public final class EmailService
{
   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailService()
   {
   }

   /*******************************************************************************
    ** Queue with the part 1 renderer.
    *******************************************************************************/
   public static EmailSendResult send(EmailRequest request) throws QException
   {
      return (send(request, new AdHocEmailRenderer()));
   }

   /*******************************************************************************
    ** Queue with a renderer supplied by the host or a later phase.
    *******************************************************************************/
   public static EmailSendResult send(EmailRequest request, EmailRenderer renderer) throws QException
   {
      EmailQBitConfig config = EmailRuntime.config();
      if(config == null)
      {
         return (refused("Email qbit is not installed"));
      }
      if(CorePermissionEnforcer.shouldEnforce(new SendMessageInput()) && !EmailPermissionChecker.has(EmailPermissions.SEND))
      {
         return (refused("Sending email needs email.send"));
      }
      if(request == null)
      {
         return (refused("An email request is required"));
      }
      if(request.getTemplateName() != null)
      {
         return (refused("templates arrive in part 2"));
      }
      if(!request.getVariables().isEmpty() || request.getBrandName() != null)
      {
         return (refused("Template variables and branding arrive in part 2"));
      }
      if(request.getSubject() == null || request.getSubject().contains("\r") || request.getSubject().contains("\n")
         || (request.getMarkdownBody() == null && request.getHtmlBody() == null && request.getTextBody() == null))
      {
         return (refused("A subject and message body are required; subject must be one line"));
      }
      EmailCategory category = request.getCategory() == null ? EmailCategory.TRANSACTIONAL : request.getCategory();
      QBackendTransaction transaction = request.getTransaction();
      boolean sameBackend = transaction == null || request.getTransactionBackendName() == null || config.getBackendName().equals(request.getTransactionBackendName());
      QBackendTransaction queryTransaction = sameBackend ? transaction : null;
      QRecord duplicate = duplicate(request.getIdempotencyKey(), queryTransaction);
      if(duplicate != null)
      {
         return (new EmailSendResult(duplicate.getValueInteger("id"), EmailSendResult.Status.DUPLICATE, List.of()));
      }
      String providerName = request.getProviderName() == null ? config.getDefaultProviderName() : request.getProviderName();
      QQueryFilter providerFilter = new QQueryFilter(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true));
      providerFilter.withCriteria(new QFilterCriteria(providerName == null ? "isDefault" : "name", QCriteriaOperator.EQUALS, providerName == null ? true : providerName));
      List<QRecord> providers = SystemWrites.query(EmailTables.PROVIDER, providerFilter, queryTransaction);
      if(providers.size() != 1)
      {
         return (refused("An enabled named or default provider is required"));
      }
      QRecord provider = providers.get(0);
      List<String> providerErrors = EmailTableCustomizers.validateProvider(provider, config);
      if(!providerErrors.isEmpty())
      {
         return (new EmailSendResult(null, EmailSendResult.Status.REFUSED, providerErrors));
      }
      List<String> to;
      List<String> cc;
      List<String> bcc;
      String from;
      String replyTo;
      try
      {
         to = addresses(request.getTo());
         cc = addresses(request.getCc());
         bcc = addresses(request.getBcc());
         String sender = request.getFrom() == null ? provider.getValueString("fromAddress") : request.getFrom();
         from = single(sender);
         if(request.getFrom() == null && provider.getValueString("fromName") != null)
         {
            from = new InternetAddress(new InternetAddress(from).getAddress(), provider.getValueString("fromName"), "UTF-8").toString();
         }
         String reply = request.getReplyTo() == null ? provider.getValueString("replyTo") : request.getReplyTo();
         replyTo = reply == null ? null : single(reply);
         String domain = provider.getValueString("sendingDomain");
         String address = new InternetAddress(from).getAddress();
         if(domain != null && !domain.equalsIgnoreCase(address.substring(address.lastIndexOf('@') + 1)))
         {
            return (refused("From address domain must match sending domain"));
         }
      }
      catch(Exception e)
      {
         return (refused("One or more email addresses are invalid"));
      }
      if(to.isEmpty() && cc.isEmpty() && bcc.isEmpty())
      {
         return (refused("At least one recipient is required"));
      }
      Set<String> blocked = suppressed(category, queryTransaction);
      List<String> dropped = new ArrayList<>();
      for(List<String> addresses : List.of(to, cc, bcc))
      {
         addresses.removeIf(address ->
         {
            try
            {
               if(blocked.contains(new InternetAddress(address).getAddress().toLowerCase(Locale.ROOT)))
               {
                  dropped.add(address);
                  return (true);
               }
            }
            catch(Exception e)
            {
               // Addresses were validated above.
            }
            return (false);
         });
      }
      boolean allSuppressed = to.isEmpty() && cc.isEmpty() && bcc.isEmpty();
      EmailRenderer.Rendered rendered = renderer.render(request);
      Instant now = EmailRuntime.clock().instant();
      QRecord row = new QRecord().withValue("providerId", provider.getValue("id"))
         .withValue("status", allSuppressed ? "SUPPRESSED" : "QUEUED").withValue("finishedDate", allSuppressed ? now : null).withValue("attemptCount", 0)
         .withValue("toAddresses", String.join(",", to)).withValue("ccAddresses", String.join(",", cc)).withValue("bccAddresses", String.join(",", bcc))
         .withValue("fromAddress", from).withValue("replyTo", replyTo).withValue("subject", request.getSubject())
         .withValue("htmlBody", rendered.html()).withValue("textBody", rendered.text()).withValue("bodySha256", digest(rendered))
         .withValue("category", category.name()).withValue("locale", request.getLocale()).withValue("idempotencyKey", request.getIdempotencyKey())
         .withValue("linkedTableName", request.getLinkedTableName()).withValue("linkedRecordId", request.getLinkedRecordId())
         .withValue("senderQbit", request.getSenderQbit()).withValue("senderProcess", request.getSenderProcess())
         .withValue("sendAfter", request.getSendAfter()).withValue("nextAttemptDate", request.getSendAfter() == null ? now : request.getSendAfter())
         .withValue("suppressedAddresses", String.join(",", dropped));
      for(var field : config.getSecurityFields())
      {
         row.setValue(field.getName(), request.getSecurityValues().get(field.getName()));
      }
      if(transaction != null && !sameBackend)
      {
         transaction.addAfterCommitCallback(() ->
         {
            try
            {
               if(insert(row, null).status() == EmailSendResult.Status.REFUSED)
               {
                  throw (new QException("Email after-commit enqueue failed"));
               }
            }
            catch(QException e)
            {
               // Core logs callback failures; propagate only a sanitized message.
               throw (new IllegalStateException("Email after-commit enqueue failed"));
            }
         });
         return (new EmailSendResult(null, allSuppressed ? EmailSendResult.Status.SUPPRESSED : EmailSendResult.Status.QUEUED,
            List.of("Enqueue deferred until caller transaction commits")));
      }
      return (insert(row, transaction));
   }

   /*******************************************************************************
    ** Insert once; the database unique key arbitrates concurrent requests.
    *******************************************************************************/
   private static EmailSendResult insert(QRecord row, QBackendTransaction caller) throws QException
   {
      if(caller != null)
      {
         return (insertInTransaction(row, caller));
      }
      try(QBackendTransaction transaction = QBackendTransaction.openFor(new InsertInput(EmailTables.MESSAGE)))
      {
         EmailSendResult result = insertInTransaction(row, transaction);
         if(result.status() == EmailSendResult.Status.REFUSED)
         {
            transaction.rollback();
         }
         else
         {
            transaction.commit();
            return (result);
         }
      }
      QRecord existing = duplicate(row.getValueString("idempotencyKey"), null);
      return (existing == null ? refused("Could not queue the email message") : new EmailSendResult(existing.getValueInteger("id"), EmailSendResult.Status.DUPLICATE, List.of()));
   }

   /*******************************************************************************
    ** Insert using the caller transaction, returning only safe diagnostics.
    *******************************************************************************/
   private static EmailSendResult insertInTransaction(QRecord row, QBackendTransaction transaction) throws QException
   {
      // A shared provider-row gate prevents a duplicate-key failure from aborting
      // a caller-owned SQL transaction. The unique index remains the final arbiter.
      if(row.getValueString("idempotencyKey") != null)
      {
         List<QRecord> providers = SystemWrites.query(EmailTables.PROVIDER, new QQueryFilter().withOrderBy(new QFilterOrderBy("id")).withLimit(1), transaction);
         if(!providers.isEmpty())
         {
            EmailStore.lockProvider(providers.get(0).getValueInteger("id"), transaction);
         }
      }
      QRecord existing = duplicate(row.getValueString("idempotencyKey"), transaction);
      if(existing != null)
      {
         return (new EmailSendResult(existing.getValueInteger("id"), EmailSendResult.Status.DUPLICATE, List.of()));
      }
      QRecord inserted;
      try
      {
         inserted = SystemWrites.insert(EmailTables.MESSAGE, List.of(row), transaction).getRecords().get(0);
      }
      catch(QException e)
      {
         return (refused("Could not queue the email message"));
      }
      if(inserted.getErrors() != null && !inserted.getErrors().isEmpty())
      {
         return (refused("Could not queue the email message"));
      }
      return (new EmailSendResult(inserted.getValueInteger("id"), "SUPPRESSED".equals(row.getValueString("status"))
         ? EmailSendResult.Status.SUPPRESSED : EmailSendResult.Status.QUEUED, List.of()));
   }

   /*******************************************************************************
    ** Look up an idempotency key in the appropriate transaction.
    *******************************************************************************/
   private static QRecord duplicate(String key, QBackendTransaction transaction) throws QException
   {
      if(key == null)
      {
         return (null);
      }
      List<QRecord> rows = SystemWrites.query(EmailTables.MESSAGE, new QQueryFilter(new QFilterCriteria("idempotencyKey", QCriteriaOperator.EQUALS, key)), transaction);
      return (rows.isEmpty() ? null : rows.get(0));
   }

   /*******************************************************************************
    ** Suppression matching; opt-outs never suppress transactional or security mail.
    *******************************************************************************/
   private static Set<String> suppressed(EmailCategory category, QBackendTransaction transaction) throws QException
   {
      Set<String> result = new LinkedHashSet<>();
      boolean optOut = category == EmailCategory.NOTIFICATION || category == EmailCategory.MARKETING;
      for(QRecord row : SystemWrites.query(EmailTables.SUPPRESSION, new QQueryFilter(), transaction))
      {
         Instant expires = row.getValueInstant("expiresDate");
         if(expires != null && !expires.isAfter(EmailRuntime.clock().instant()))
         {
            continue;
         }
         if(!optOut && "UNSUBSCRIBE".equals(row.getValueString("reason")))
         {
            continue;
         }
         if("ALL".equals(row.getValueString("scope")) || (optOut && category.name().equals(row.getValueString("scope"))))
         {
            result.add(row.getValueString("address").toLowerCase(Locale.ROOT));
         }
      }
      return (result);
   }

   /*******************************************************************************
    ** Strict address parsing, rejecting header injection and local-only addresses.
    *******************************************************************************/
   public static List<String> addresses(List<String> input) throws Exception
   {
      List<String> result = new ArrayList<>();
      for(String value : input)
      {
         if(value == null || value.contains("\r") || value.contains("\n"))
         {
            throw (new IllegalArgumentException("Invalid address"));
         }
         for(InternetAddress address : InternetAddress.parse(value, true))
         {
            address.validate();
            if(address.isGroup() || !address.getAddress().contains("@"))
            {
               throw (new IllegalArgumentException("Invalid address"));
            }
            result.add(address.toString());
         }
      }
      return (result);
   }

   /*******************************************************************************
    ** One sender or reply-to mailbox.
    *******************************************************************************/
   private static String single(String value) throws Exception
   {
      List<String> addresses = addresses(List.of(value));
      if(addresses.size() != 1)
      {
         throw (new IllegalArgumentException("One address required"));
      }
      return (addresses.get(0));
   }

   /*******************************************************************************
    ** Keep a digest after body retention clears both alternatives.
    *******************************************************************************/
   private static String digest(EmailRenderer.Rendered rendered)
   {
      try
      {
         return (HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((rendered.html() + "\u0000" + rendered.text()).getBytes(StandardCharsets.UTF_8))));
      }
      catch(java.security.NoSuchAlgorithmException e)
      {
         throw (new IllegalStateException("SHA-256 unavailable"));
      }
   }

   /*******************************************************************************
    ** Refusal with no stored row.
    *******************************************************************************/
   private static EmailSendResult refused(String reason)
   {
      return (new EmailSendResult(null, EmailSendResult.Status.REFUSED, List.of(reason)));
   }
}
