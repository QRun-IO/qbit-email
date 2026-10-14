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

package com.kingsrook.qbits.email.api;


import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import com.kingsrook.qbits.email.EmailRuntime;
import com.kingsrook.qbits.email.metadata.EmailTableCustomizers;
import com.kingsrook.qbits.email.metadata.EmailTables;
import com.kingsrook.qbits.email.outbox.EmailEffects;
import com.kingsrook.qbits.email.outbox.EmailService;
import com.kingsrook.qbits.email.support.EmailApiMapper;
import com.kingsrook.qbits.email.support.EmailStore;
import com.kingsrook.qbits.email.support.SystemWrites;
import com.kingsrook.qbits.email.template.EmailTemplateRenderer;
import com.kingsrook.qbits.email.template.EmailTemplates;
import com.kingsrook.qbits.email.template.VariableSchema;
import com.kingsrook.qqq.backend.core.actions.tables.QueryAction;
import com.kingsrook.qqq.backend.core.context.QContext;
import com.kingsrook.qqq.backend.core.exceptions.QException;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QCriteriaOperator;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterCriteria;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QFilterOrderBy;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QQueryFilter;
import com.kingsrook.qqq.backend.core.model.actions.tables.query.QueryInput;
import com.kingsrook.qqq.backend.core.model.data.QRecord;
import com.kingsrook.qqq.backend.core.model.metadata.QInstance;


/*******************************************************************************
 ** Stable contract for other qbits. Changes within a 0.x minor release line are
 ** additive only; implementation packages are not API and may change.
 ** Database operations use the current QContext. Reads run as the email system
 ** and return only the documented projection; callers must authorize any
 ** exposure of this metadata to their own users. Sends retain all existing
 ** permission, validation, PHI, suppression and recipient checks.
 *******************************************************************************/
public final class EmailApi
{
   private static final Set<String> MESSAGE_FIELDS = Set.of("id", "status", "category", "idempotencyKey", "rfcMessageId", "providerMessageId",
      "templateId", "linkedTableName", "linkedRecordId", "createDate", "sentDate", "deliveredDate");



   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailApi()
   {
   }



   /*******************************************************************************
    ** True only when the email qbit was produced in the supplied instance.
    ** Does not require a current context; null is not installed.
    *******************************************************************************/
   public static boolean isInstalled(QInstance instance)
   {
      return (EmailRuntime.config(instance) != null);
   }



   /*******************************************************************************
    ** Queue a message. QUEUED is not a delivery acknowledgement. Null requests
    ** are refused by the same service used by core messaging.
    *******************************************************************************/
   public static EmailApiResult send(EmailApiRequest request) throws QException
   {
      return (EmailApiMapper.result(EmailService.send(EmailApiMapper.request(request))));
   }



   /*******************************************************************************
    ** Commit one durable handoff with the effect key as the idempotency key.
    ** Refuse null requests, blank or over-500-character keys, conflicting keys
    ** and caller transactions before changing the request. Suppression is REFUSED,
    ** including replays; a retained audit row is returned. Success acknowledges
    ** committed storage, not delivery. Database failures propagate as QException.
    *******************************************************************************/
   public static EmailApiEffectResult sendOnce(EmailApiRequest request, String effectKey) throws QException
   {
      if(request == null || effectKey == null || effectKey.isBlank() || effectKey.length() > 500
         || request.getTransaction() != null
         || request.getIdempotencyKey() != null && !request.getIdempotencyKey().equals(effectKey))
      {
         return (new EmailApiEffectResult(EmailApiEffectOutcome.REFUSED, null));
      }
      request.withIdempotencyKey(effectKey);
      var result = EmailEffects.send(EmailApiMapper.request(request), effectKey);
      EmailApiMessage message = result.message() == null ? null : getMessage(result.message().id())
         .orElseThrow(() -> new QException("Queued email reference is unavailable"));
      return (new EmailApiEffectResult(EmailApiEffectOutcome.valueOf(result.outcome().name()), message));
   }



   /*******************************************************************************
    ** Create a validated template and active version once. Returns false when
    ** it already exists. Invalid definitions throw IllegalArgumentException.
    ** Does not grant PHI approval or overwrite administrator edits.
    *******************************************************************************/
   public static boolean ensureTemplate(EmailApiTemplate template) throws QException
   {
      return (EmailTemplates.ensure(EmailApiMapper.template(template)));
   }



   /*******************************************************************************
    ** System lookup of delivery metadata. Null and unknown ids return empty.
    *******************************************************************************/
   public static Optional<EmailApiMessage> getMessage(Integer messageId) throws QException
   {
      if(messageId == null)
      {
         return (Optional.empty());
      }
      List<QRecord> rows = read(EmailTables.MESSAGE, EmailStore.equal("id", messageId).withLimit(1), MESSAGE_FIELDS);
      return (rows.isEmpty() ? Optional.empty() : Optional.of(message(rows.getFirst())));
   }



   /*******************************************************************************
    ** System lookup by exact effect or application key. Null, blank and unknown
    ** keys return empty. Returns current metadata without message content.
    *******************************************************************************/
   public static Optional<EmailApiMessage> getMessageByIdempotencyKey(String key) throws QException
   {
      return (findMessage("idempotencyKey", key));
   }



   /*******************************************************************************
    ** System lookup by the exact bracketed RFC Message-ID stored at enqueue time.
    ** Null, blank and unknown ids return empty; provider response ids are distinct.
    *******************************************************************************/
   public static Optional<EmailApiMessage> getMessageByRfcMessageId(String rfcMessageId) throws QException
   {
      return (findMessage("rfcMessageId", rfcMessageId));
   }



   /*******************************************************************************
    ** Share exact-key lookup semantics and the metadata-only projection.
    *******************************************************************************/
   private static Optional<EmailApiMessage> findMessage(String field, String value) throws QException
   {
      if(value == null || value.isBlank())
      {
         return (Optional.empty());
      }
      List<QRecord> rows = read(EmailTables.MESSAGE, EmailStore.equal(field, value).withLimit(1), MESSAGE_FIELDS);
      return (rows.isEmpty() ? Optional.empty() : Optional.of(message(rows.getFirst())));
   }



   /*******************************************************************************
    ** System lookup by literal prefix, ordered by ascending message id. Prefix
    ** must be nonblank and limit positive; invalid arguments are rejected.
    ** Returns at most limit rows, without message content or recipient data.
    *******************************************************************************/
   public static List<EmailApiMessage> findMessagesByIdempotencyKeyPrefix(String prefix, int limit) throws QException
   {
      if(prefix == null || prefix.isBlank() || limit < 1)
      {
         throw (new IllegalArgumentException("A nonblank idempotency key prefix and a positive limit are required"));
      }
      ///////////////////////////////////////////////////////////////////////////
      // QQQ SQL STARTS_WITH uses LIKE without escaping. Narrow by the literal  //
      // portion before any wildcard, then check the full prefix in Java. Page //
      // by id so wildcard false positives cannot consume the requested limit. //
      ///////////////////////////////////////////////////////////////////////////
      String candidatePrefix = prefix.split("[%_\\\\]", 2)[0];
      List<EmailApiMessage> messages = new ArrayList<>();
      int lastId = 0;
      while(messages.size() < limit)
      {
         int batchSize = Math.min(500, limit - messages.size());
         QQueryFilter filter = new QQueryFilter(new QFilterCriteria("idempotencyKey", QCriteriaOperator.STARTS_WITH, candidatePrefix))
            .withCriteria(new QFilterCriteria("id", QCriteriaOperator.GREATER_THAN, lastId))
            .withOrderBy(new QFilterOrderBy("id")).withLimit(batchSize);
         List<QRecord> rows = read(EmailTables.MESSAGE, filter, MESSAGE_FIELDS);
         for(QRecord row : rows)
         {
            lastId = row.getValueInteger("id");
            if(row.getValueString("idempotencyKey") != null && row.getValueString("idempotencyKey").startsWith(prefix))
            {
               messages.add(message(row));
            }
         }
         if(rows.size() < batchSize)
         {
            break;
         }
      }
      return (List.copyOf(messages));
   }



   /*******************************************************************************
    ** Enabled sender rows of an enabled named provider, ordered by id. Null
    ** selects the effective default provider. No match returns an empty list.
    ** The provider's legacy from-address fallback is not a sender row.
    *******************************************************************************/
   public static List<EmailApiSenderAddress> listSenderAddresses(String providerName) throws QException
   {
      QRecord provider = provider(QContext.getQInstance(), providerName);
      if(provider == null)
      {
         return (List.of());
      }
      QQueryFilter filter = EmailStore.equal("providerId", provider.getValueInteger("id"))
         .withCriteria(new QFilterCriteria("enabled", QCriteriaOperator.EQUALS, true)).withOrderBy(new QFilterOrderBy("id"));
      return (read(EmailTables.SENDER, filter, Set.of("id", "address", "displayName", "isDefault"))
         .stream().map(row -> new EmailApiSenderAddress(row.getValueInteger("id"), row.getValueString("address"),
            row.getValueString("displayName"), Boolean.TRUE.equals(row.getValueBoolean("isDefault")))).toList());
   }



   /*******************************************************************************
    ** Effective enabled default: the configured name takes precedence over the
    ** default row. Empty when absent, disabled or ambiguous. An installed
    ** instance must be the current QContext instance, otherwise this throws.
    *******************************************************************************/
   public static Optional<String> defaultProviderName(QInstance instance) throws QException
   {
      QRecord provider = provider(instance, null);
      return (provider == null ? Optional.empty() : Optional.of(provider.getValueString("name")));
   }



   /*******************************************************************************
    ** Preflight the default sending configuration for a category. Null category
    ** means TRANSACTIONAL. This does not validate a particular template,
    ** recipient or permission, or contact a transport. PHI callers use the
    ** overload with phiApproved set to true.
    *******************************************************************************/
   public static EmailApiReadiness readiness(QInstance instance, EmailApiCategory category) throws QException
   {
      return (readiness(instance, category, false));
   }



   /*******************************************************************************
    ** Also check prerequisites for a PHI-approved template. PHI is a template
    ** property, not an email category. Checks body key availability and provider
    ** PHI/tracking policy; does not approve a template. Resolver failures are
    ** reported as missing keys without exposing exception details or values.
    *******************************************************************************/
   public static EmailApiReadiness readiness(QInstance instance, EmailApiCategory category, boolean phiApproved) throws QException
   {
      List<String> reasons = new ArrayList<>();
      Set<EmailApiReadinessCheck> failed = EnumSet.noneOf(EmailApiReadinessCheck.class);
      var config = EmailRuntime.config(instance);
      if(config == null)
      {
         return (new EmailApiReadiness(false, List.of("Email qbit is not installed"), Set.of(EmailApiReadinessCheck.NOT_INSTALLED)));
      }
      QRecord provider = provider(instance, null);
      if(provider == null)
      {
         reasons.add("An enabled named or default provider is required");
         failed.add(EmailApiReadinessCheck.PROVIDER_MISSING);
      }
      else
      {
         if(!EmailTableCustomizers.validateProvider(provider, config).isEmpty())
         {
            reasons.add("The default provider configuration is invalid");
            failed.add(EmailApiReadinessCheck.PROVIDER_INVALID);
         }
         if(phiApproved)
         {
            try
            {
               EmailTemplateRenderer.checkProvider(provider);
            }
            catch(IllegalArgumentException e)
            {
               reasons.add("PHI email needs a PHI-enabled provider with open and click tracking disabled");
               failed.add(EmailApiReadinessCheck.PHI_PROVIDER);
            }
         }
      }
      if(category == EmailApiCategory.MARKETING)
      {
         try
         {
            if(config.getPublicBaseUrl() == null || config.getPublicBaseUrl().isBlank())
            {
               throw (new IllegalArgumentException());
            }
            VariableSchema.url(config.getPublicBaseUrl());
         }
         catch(IllegalArgumentException e)
         {
            reasons.add("MARKETING email needs a valid publicBaseUrl");
            failed.add(EmailApiReadinessCheck.PUBLIC_BASE_URL);
         }
         if(!hasSecret(config.getSecretResolver(), "QQQ_EMAIL_UNSUBSCRIBE_KEY"))
         {
            reasons.add("MARKETING email needs QQQ_EMAIL_UNSUBSCRIBE_KEY");
            failed.add(EmailApiReadinessCheck.UNSUBSCRIBE_KEY);
         }
         List<QRecord> brands = read(EmailTables.BRAND, EmailStore.equal("isDefault", true).withLimit(2), Set.of("id", "postalAddress"));
         if(brands.size() != 1 || brands.getFirst().getValueString("postalAddress") == null || brands.getFirst().getValueString("postalAddress").isBlank())
         {
            reasons.add("MARKETING email needs a default brand with a postal address");
            failed.add(EmailApiReadinessCheck.BRAND_POSTAL_ADDRESS);
         }
      }
      if(phiApproved && !hasSecret(config.getSecretResolver(), "QQQ_EMAIL_BODY_KEY"))
      {
         reasons.add("PHI email needs QQQ_EMAIL_BODY_KEY");
         failed.add(EmailApiReadinessCheck.BODY_KEY);
      }
      return (new EmailApiReadiness(reasons.isEmpty(), reasons, failed));
   }



   /*******************************************************************************
    ** Presence only; neither retain values nor expose resolver failures.
    *******************************************************************************/
   private static boolean hasSecret(Function<String, String> resolver, String name)
   {
      try
      {
         String value = resolver.apply(name);
         return (value != null && !value.isBlank());
      }
      catch(RuntimeException e)
      {
         return (false);
      }
   }



   /*******************************************************************************
    ** Match the enqueue service's configured-name/default-row precedence.
    ** Refuse a mismatched context instead of reading another instance's data.
    *******************************************************************************/
   private static QRecord provider(QInstance instance, String name) throws QException
   {
      var config = EmailRuntime.config(instance);
      if(config == null)
      {
         return (null);
      }
      if(instance != QContext.getQInstance())
      {
         throw (new IllegalArgumentException("The supplied instance must be the current QContext instance"));
      }
      String effectiveName = name == null ? config.getDefaultProviderName() : name;
      QQueryFilter filter = EmailStore.equal("enabled", true)
         .withCriteria(new QFilterCriteria(effectiveName == null ? "isDefault" : "name", QCriteriaOperator.EQUALS, effectiveName == null ? true : effectiveName))
         .withLimit(2);
      List<QRecord> providers = SystemWrites.query(EmailTables.PROVIDER, filter);
      return (providers.size() == 1 ? providers.getFirst() : null);
   }



   /*******************************************************************************
    ** Read an explicit projection; message bodies are never fetched here.
    *******************************************************************************/
   private static List<QRecord> read(String table, QQueryFilter filter, Set<String> fields) throws QException
   {
      return (SystemWrites.call(() -> new QueryAction().execute(new QueryInput(table).withFilter(filter)
         .withFieldNamesToInclude(fields).withShouldFetchHeavyFields(false).withShouldOmitHiddenFields(false)).getRecords()));
   }



   /*******************************************************************************
    ** Resolve the template name and copy only the approved metadata fields.
    *******************************************************************************/
   private static EmailApiMessage message(QRecord row) throws QException
   {
      String templateName = null;
      if(row.getValueInteger("templateId") != null)
      {
         List<QRecord> templates = read(EmailTables.TEMPLATE, EmailStore.equal("id", row.getValueInteger("templateId")).withLimit(1), Set.of("id", "name"));
         if(!templates.isEmpty())
         {
            templateName = templates.getFirst().getValueString("name");
         }
      }
      String category = row.getValueString("category");
      return (new EmailApiMessage(row.getValueInteger("id"), row.getValueString("status"), category == null ? null : EmailApiCategory.valueOf(category),
         row.getValueString("idempotencyKey"), row.getValueString("rfcMessageId"), row.getValueString("providerMessageId"), templateName, row.getValueString("linkedTableName"),
         row.getValueString("linkedRecordId"), row.getValueInstant("createDate"), row.getValueInstant("sentDate"), row.getValueInstant("deliveredDate")));
   }
}
