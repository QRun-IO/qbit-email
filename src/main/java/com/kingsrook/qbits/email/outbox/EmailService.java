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
import com.kingsrook.qbits.email.template.EmailTemplateRenderer;
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
    ** Queue validated, rendered alternatives with optional versioned templates.
    *******************************************************************************/
   public static EmailSendResult send(EmailRequest request) throws QException
   {
      return (send(request, new AdHocEmailRenderer()));
   }

   /*******************************************************************************
    ** Queue with a host-supplied ad-hoc renderer; templates always use the validated engine.
    *******************************************************************************/
   public static EmailSendResult send(EmailRequest request, EmailRenderer renderer) throws QException
   {
      return (send(request, renderer, null));
   }

   /*******************************************************************************
    ** Shared enqueue path, including already protected retained content.
    *******************************************************************************/
   private static EmailSendResult send(EmailRequest request, EmailRenderer renderer, QRecord retained) throws QException
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
      EmailTemplateRenderer templateRenderer;
      QRecord templateContent;
      try
      {
         templateRenderer = request.getTemplateName() == null ? null : EmailTemplateRenderer.prepare(request);
         templateContent = templateRenderer == null ? retained : templateRenderer.render("pending", "https://example.invalid/unsubscribe/pending", true);
      }
      catch(IllegalArgumentException e)
      {
         return (refused(e.getMessage()));
      }
      if(templateRenderer == null && (!request.getVariables().isEmpty() || !request.getRecordVariables().isEmpty()))
      {
         return (refused("Variables require a template with a schema"));
      }
      if(templateRenderer == null && (request.getSubject() == null || request.getSubject().contains("\r") || request.getSubject().contains("\n")
         || (request.getMarkdownBody() == null && request.getHtmlBody() == null && request.getTextBody() == null)))
      {
         return (refused("A subject and message body are required; subject must be one line"));
      }
      EmailCategory category = templateContent == null ? (request.getCategory() == null ? EmailCategory.TRANSACTIONAL : request.getCategory())
         : EmailCategory.valueOf(templateContent.getValueString("category"));
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
      try
      {
         if(templateRenderer != null)
         {
            templateRenderer.validateProvider(provider);
         }
         else if(retained != null && Boolean.TRUE.equals(retained.getValueBoolean("phiApproved")))
         {
            EmailTemplateRenderer.checkProvider(provider);
            com.kingsrook.qbits.email.phi.EmailBodyEncryption.encrypt(null, "subject", "pending");
         }
      }
      catch(IllegalArgumentException e)
      {
         return (refused(e.getMessage()));
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
      EmailRenderer.Rendered rendered;
      QRecord adHocBrand = null;
      try
      {
         rendered = templateContent == null ? renderer.render(request) : new EmailRenderer.Rendered(templateContent.getValueString("htmlBody"), templateContent.getValueString("textBody"));
         if(templateContent == null)
         {
            adHocBrand = com.kingsrook.qbits.email.render.EmailBrands.resolve(request.getBrandName(), null);
         }
      }
      catch(IllegalArgumentException e)
      {
         return (refused(e.getMessage()));
      }
      Instant now = EmailRuntime.clock().instant();
      QRecord row = new QRecord().withValue("providerId", provider.getValue("id"))
         .withValue("status", allSuppressed ? "SUPPRESSED" : "QUEUED").withValue("finishedDate", allSuppressed ? now : null).withValue("attemptCount", 0)
         .withValue("toAddresses", String.join(",", to)).withValue("ccAddresses", String.join(",", cc)).withValue("bccAddresses", String.join(",", bcc))
         .withValue("fromAddress", from).withValue("replyTo", replyTo).withValue("subject", request.getSubject())
         .withValue("htmlBody", rendered.html()).withValue("textBody", rendered.text()).withValue("bodySha256", digest(rendered))
         .withValue("brandId", adHocBrand == null ? null : adHocBrand.getValue("id"))
         .withValue("category", category.name()).withValue("locale", request.getLocale()).withValue("idempotencyKey", request.getIdempotencyKey())
         .withValue("linkedTableName", request.getLinkedTableName()).withValue("linkedRecordId", request.getLinkedRecordId())
         .withValue("senderQbit", request.getSenderQbit()).withValue("senderProcess", request.getSenderProcess())
         .withValue("sendAfter", request.getSendAfter()).withValue("nextAttemptDate", request.getSendAfter() == null ? now : request.getSendAfter())
         .withValue("suppressedAddresses", String.join(",", dropped));
      if(templateContent != null)
      {
         row.getValues().putAll(templateContent.getValues());
      }
      try
      {
         com.kingsrook.qbits.email.render.EmailUnsubscribeLinks.create("pending", String.join(",", to), category.name());
      }
      catch(IllegalArgumentException e)
      {
         return (refused(e.getMessage()));
      }
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
               if(insert(row, null, templateRenderer).status() == EmailSendResult.Status.REFUSED)
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
      return (insert(row, transaction, templateRenderer));
   }

   /*******************************************************************************
    ** Resend retained content without decrypting it or downgrading PHI policy.
    ** Snapshot tokens keep the original binding; the new row links to that row.
    *******************************************************************************/
   public static EmailSendResult resend(Integer id) throws QException
   {
      if(!SystemWrites.active() && !EmailPermissionChecker.has(EmailPermissions.MANAGE))
      {
         return (refused("Resending email needs email.manage"));
      }
      QRecord row = EmailStore.get(EmailTables.MESSAGE, id, null);
      if(row == null || row.getValueString("htmlBody") == null && row.getValueString("textBody") == null)
      {
         return (refused("Retained message content is unavailable"));
      }
      QRecord provider = EmailStore.get(EmailTables.PROVIDER, row.getValueInteger("providerId"), null);
      if(provider == null)
      {
         return (refused("Provider is unavailable"));
      }
      EmailRequest request = new EmailRequest().withSubject(row.getValueString("subject")).withHtmlBody(row.getValueString("htmlBody"))
         .withTextBody(row.getValueString("textBody")).withFrom(row.getValueString("fromAddress")).withReplyTo(row.getValueString("replyTo"))
         .withProviderName(provider.getValueString("name")).withCategory(EmailCategory.valueOf(row.getValueString("category")))
         .withLinkedRecord(EmailTables.MESSAGE, id).withSender("email", "email_resend");
      if(row.getValueString("toAddresses") != null && !row.getValueString("toAddresses").isBlank())
      {
         request.withTo(row.getValueString("toAddresses"));
      }
      if(row.getValueString("ccAddresses") != null && !row.getValueString("ccAddresses").isBlank())
      {
         request.withCc(row.getValueString("ccAddresses"));
      }
      if(row.getValueString("bccAddresses") != null && !row.getValueString("bccAddresses").isBlank())
      {
         request.withBcc(row.getValueString("bccAddresses"));
      }
      for(var field : EmailRuntime.config().getSecurityFields())
      {
         request.getSecurityValues().put(field.getName(), row.getValue(field.getName()));
      }
      QRecord content = new QRecord();
      for(String field : List.of("subject", "htmlBody", "textBody", "variablesSnapshot", "phiApproved", "templateId", "templateVersionId", "brandId", "locale", "category", "unsubscribeUrl"))
      {
         content.setValue(field, row.getValue(field));
      }
      content.withValue("encryptionSourceId", row.getValueString("id"));
      return (send(request, ignored -> new EmailRenderer.Rendered(row.getValueString("htmlBody"), row.getValueString("textBody")), content));
   }

   /*******************************************************************************
    ** Insert once; the database unique key arbitrates concurrent requests.
    *******************************************************************************/
   private static EmailSendResult insert(QRecord row, QBackendTransaction caller, EmailTemplateRenderer templateRenderer) throws QException
   {
      if(caller != null)
      {
         return (insertInTransaction(row, caller, templateRenderer));
      }
      try(QBackendTransaction transaction = QBackendTransaction.openFor(new InsertInput(EmailTables.MESSAGE)))
      {
         EmailSendResult result = insertInTransaction(row, transaction, templateRenderer);
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
   private static EmailSendResult insertInTransaction(QRecord row, QBackendTransaction transaction, EmailTemplateRenderer templateRenderer) throws QException
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
      try
      {
         String unsubscribe = row.getValueString("unsubscribeUrl");
         if(unsubscribe == null && (templateRenderer != null || !Boolean.TRUE.equals(row.getValueBoolean("phiApproved"))))
         {
            unsubscribe = com.kingsrook.qbits.email.render.EmailUnsubscribeLinks.create(inserted.getValueString("id"),
               row.getValueString("toAddresses") + "," + row.getValueString("ccAddresses") + "," + row.getValueString("bccAddresses"), row.getValueString("category"));
         }
         QRecord content;
         if(templateRenderer != null)
         {
            content = templateRenderer.render(inserted.getValueString("id"), unsubscribe, true);
         }
         else
         {
            content = new QRecord();
            if(Boolean.TRUE.equals(row.getValueBoolean("phiApproved")))
            {
               for(String field : List.of("subject", "htmlBody", "textBody"))
               {
                  content.setValue(field, com.kingsrook.qbits.email.phi.EmailBodyEncryption.rebind(row.getValueString(field), field,
                     row.getValueString("encryptionSourceId"), inserted.getValueString("id")));
               }
            }
            if(unsubscribe != null && row.getValueString("unsubscribeUrl") == null)
            {
               String link = "<p><a href=\"" + com.kingsrook.qbits.email.template.EmailTemplateEngine.escape(unsubscribe) + "\">Unsubscribe</a></p>";
               String html = row.getValueString("htmlBody");
               java.util.regex.Matcher end = java.util.regex.Pattern.compile("</footer>|</body>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
               content.withValue("htmlBody", end.find() ? html.substring(0, end.start()) + link + html.substring(end.start()) : html + link)
                  .withValue("textBody", row.getValueString("textBody") + "\nUnsubscribe (" + unsubscribe + ")");
            }
         }
         content.withValue("id", inserted.getValue("id")).withValue("unsubscribeUrl", unsubscribe);
         if(content.getValueString("htmlBody") != null)
         {
            content.withValue("bodySha256", digest(new EmailRenderer.Rendered(content.getValueString("htmlBody"), content.getValueString("textBody"))));
         }
         EmailStore.update(EmailTables.MESSAGE, content, transaction);
      }
      catch(IllegalArgumentException e)
      {
         SystemWrites.delete(EmailTables.MESSAGE, EmailStore.equal("id", inserted.getValueInteger("id")), transaction);
         return (refused(e.getMessage()));
      }
      return (new EmailSendResult(inserted.getValueInteger("id"), "SUPPRESSED".equals(row.getValueString("status"))
         ? EmailSendResult.Status.SUPPRESSED : EmailSendResult.Status.QUEUED, templateRenderer == null ? List.of() : templateRenderer.warnings()));
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
