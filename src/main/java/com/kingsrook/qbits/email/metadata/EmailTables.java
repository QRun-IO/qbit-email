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

package com.kingsrook.qbits.email.metadata;


import java.util.ArrayList;
import java.util.List;
import com.kingsrook.qbits.email.EmailQBitConfig;
import com.kingsrook.qbits.email.outbox.EmailCategory;
import com.kingsrook.qbits.email.permissions.EmailPermissionChecker;
import com.kingsrook.qqq.backend.core.actions.dashboard.widgets.ChildRecordListRenderer;
import com.kingsrook.qqq.backend.core.instances.QInstanceEnricher;
import com.kingsrook.qqq.backend.core.model.metadata.code.QCodeReference;
import com.kingsrook.qqq.backend.core.model.metadata.dashboard.QWidgetMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.AdornmentType;
import com.kingsrook.qqq.backend.core.model.metadata.fields.FieldAdornment;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.fields.QFieldType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinOn;
import com.kingsrook.qqq.backend.core.model.metadata.joins.JoinType;
import com.kingsrook.qqq.backend.core.model.metadata.joins.QJoinMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.layout.QIcon;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.PermissionLevel;
import com.kingsrook.qqq.backend.core.model.metadata.permissions.QPermissionRules;
import com.kingsrook.qqq.backend.core.model.metadata.possiblevalues.QPossibleValueSource;
import com.kingsrook.qqq.backend.core.model.metadata.tables.Capability;
import com.kingsrook.qqq.backend.core.model.metadata.tables.QTableMetaData;
import com.kingsrook.qqq.backend.core.model.metadata.tables.SectionFactory;
import com.kingsrook.qqq.backend.core.model.metadata.tables.UniqueKey;


/*******************************************************************************
 ** Standard visible tables, with system-owned messages and attempt claims.
 *******************************************************************************/
public final class EmailTables
{
   public static final String PROVIDER = "email_provider";
   public static final String MESSAGE = "email_message";
   public static final String ATTEMPT = "email_message_attempt";
   public static final String TEMPLATE = "email_template";
   public static final String VERSION = "email_template_version";
   public static final String BRAND = "email_brand";
   public static final String SUPPRESSION = "email_suppression";
   public static final String INBOX = "email_webhook_inbox";
   public static final String EVENT = "email_event";
   public static final List<String> ALL = List.of(PROVIDER, MESSAGE, ATTEMPT, TEMPLATE, VERSION, BRAND, SUPPRESSION, INBOX, EVENT);
   public static final List<String> MAIN = List.of(MESSAGE, TEMPLATE, BRAND, SUPPRESSION, PROVIDER);
   public static final List<String> SYSTEM_WRITTEN = List.of(MESSAGE, ATTEMPT, INBOX, EVENT);

   /*******************************************************************************
    ** Utility class.
    *******************************************************************************/
   private EmailTables()
   {
   }

   /*******************************************************************************
    ** Shared permission base and custom action checker.
    *******************************************************************************/
   public static QPermissionRules permissionRules()
   {
      return (new QPermissionRules().withLevel(PermissionLevel.HAS_ACCESS_PERMISSION).withPermissionBaseName("email")
         .withCustomPermissionChecker(new QCodeReference(EmailPermissionChecker.class)));
   }

   /*******************************************************************************
    ** All enum and table possible-value sources.
    *******************************************************************************/
   public static List<QPossibleValueSource> possibleValueSources()
   {
      List<QPossibleValueSource> result = new ArrayList<>();
      result.add(QPossibleValueSource.newForEnum("email_provider_kind", EmailEnums.ProviderKind.values()));
      result.add(QPossibleValueSource.newForEnum("email_security", EmailEnums.Security.values()));
      result.add(QPossibleValueSource.newForEnum("email_health_status", EmailEnums.HealthStatus.values()));
      result.add(QPossibleValueSource.newForEnum("email_message_status", EmailEnums.MessageStatus.values()));
      result.add(QPossibleValueSource.newForEnum("email_attempt_outcome", EmailEnums.AttemptOutcome.values()));
      result.add(QPossibleValueSource.newForEnum("email_template_kind", EmailEnums.TemplateKind.values()));
      result.add(QPossibleValueSource.newForEnum("email_version_status", EmailEnums.VersionStatus.values()));
      result.add(QPossibleValueSource.newForEnum("email_suppression_scope", EmailEnums.SuppressionScope.values()));
      result.add(QPossibleValueSource.newForEnum("email_suppression_reason", EmailEnums.SuppressionReason.values()));
      result.add(QPossibleValueSource.newForEnum("email_category", EmailCategory.values()));
      result.add(QPossibleValueSource.newForEnum("email_inbox_status", EmailEnums.InboxStatus.values()));
      result.add(QPossibleValueSource.newForEnum("email_event_type", EmailEnums.EventType.values()));
      result.add(QPossibleValueSource.newForEnum("email_bounce_type", EmailEnums.BounceType.values()));
      ALL.forEach(name -> result.add(QPossibleValueSource.newForTable(name)));
      return (result);
   }

   /*******************************************************************************
    ** Outbound delivery and tracking tables.
    *******************************************************************************/
   public static List<QTableMetaData> tables(EmailQBitConfig config)
   {
      List<QTableMetaData> tables = new ArrayList<>();
      tables.add(provider(config));
      tables.add(message(config));
      tables.add(messageattempt(config));
      tables.add(template(config));
      tables.add(templateversion(config));
      tables.add(brand(config));
      tables.add(suppression(config));
      tables.add(inbox(config));
      tables.add(event(config));
      for(QTableMetaData table : tables)
      {
         for(QFieldMetaData field : config.getSecurityFields())
         {
            table.addField(field.clone());
            table.getSections().get(0).getFieldNames().add(field.getName());
         }
         config.getRecordSecurityLocks().forEach(table::withRecordSecurityLock);
         if(SYSTEM_WRITTEN.contains(table.getName()))
         {
            table.withoutCapabilities(Capability.TABLE_INSERT, Capability.TABLE_UPDATE, Capability.TABLE_DELETE);
            table.getFields().values().forEach(f -> f.setIsEditable(false));
         }
         table.getFields().values().forEach(f -> f.setBackendName(QInstanceEnricher.inferBackendName(f.getName())));
      }
      return (tables);
   }

   /*******************************************************************************
    ** Common fields and record label.
    *******************************************************************************/
   private static QTableMetaData base(EmailQBitConfig config, String name, String label)
   {
      return (new QTableMetaData().withName(name).withLabel(label).withBackendName(config.getBackendName())
         .withPrimaryKeyField("id").withIcon(new QIcon("mail")).withPermissionRules(permissionRules())
         .withRecordLabelFormatAndFields("%s", "id")
         .withField(field("id", QFieldType.INTEGER).withIsEditable(false))
         .withField(field("createDate", QFieldType.DATE_TIME).withIsEditable(false))
         .withField(field("modifyDate", QFieldType.DATE_TIME).withIsEditable(false)));
   }

   /*******************************************************************************
    ** Field with a plain English label.
    *******************************************************************************/
   private static QFieldMetaData field(String name, QFieldType type)
   {
      String words = name.replaceAll("([a-z])([A-Z])", "$1 $2");
      return (new QFieldMetaData(name, type).withLabel(Character.toUpperCase(words.charAt(0)) + words.substring(1)));
   }


   /*******************************************************************************
    ** Metadata for email_provider.
    *******************************************************************************/
   private static QTableMetaData provider(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, PROVIDER, "Provider")
         .withField(field("name", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("label", QFieldType.STRING).withMaxLength(500))
         .withField(field("kind", QFieldType.STRING).withPossibleValueSourceName("email_provider_kind").withIsRequired(true).withMaxLength(500))
         .withField(field("enabled", QFieldType.BOOLEAN).withDefaultValue(true))
         .withField(field("isDefault", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(field("fromAddress", QFieldType.STRING).withIsSensitive(true).withMaxLength(500))
         .withField(field("fromName", QFieldType.STRING).withMaxLength(500))
         .withField(field("replyTo", QFieldType.STRING).withIsSensitive(true).withMaxLength(500))
         .withField(field("sendingDomain", QFieldType.STRING).withMaxLength(500))
         .withField(field("host", QFieldType.STRING).withMaxLength(500))
         .withField(field("port", QFieldType.INTEGER))
         .withField(field("security", QFieldType.STRING).withPossibleValueSourceName("email_security").withDefaultValue("NONE").withMaxLength(500))
         .withField(field("region", QFieldType.STRING).withMaxLength(500))
         .withField(field("usernameEnvName", QFieldType.STRING).withMaxLength(500))
         .withField(field("secretEnvName", QFieldType.STRING).withMaxLength(500))
         .withField(field("webhookSecretEnvName", QFieldType.STRING).withMaxLength(500))
         .withField(field("snsTopicArn", QFieldType.STRING).withMaxLength(500))
         .withField(field("configurationSet", QFieldType.STRING).withMaxLength(500))
         .withField(field("messageStream", QFieldType.STRING).withMaxLength(500))
         .withField(field("maxPerMinute", QFieldType.INTEGER))
         .withField(field("maxPerDay", QFieldType.INTEGER))
         .withField(field("trackOpens", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(field("trackClicks", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(field("phiAllowed", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(field("healthStatus", QFieldType.STRING).withPossibleValueSourceName("email_health_status").withDefaultValue("HEALTHY").withMaxLength(500))
         .withField(field("lastSuccessDate", QFieldType.DATE_TIME))
         .withField(field("lastFailureDate", QFieldType.DATE_TIME))
         .withField(field("lastFailureMessage", QFieldType.STRING).withMaxLength(500))
         .withField(field("dispatchLock", QFieldType.INTEGER).withDefaultValue(0).withIsHidden(true).withIsEditable(false))
         .withField(field("defaultSlot", QFieldType.INTEGER).withIsHidden(true).withIsEditable(false));
      table.withRecordLabelFormatAndFields("%s", "name");
      table.withUniqueKey(new UniqueKey("name"));
      table.withUniqueKey(new UniqueKey("defaultSlot"));
      table.withSection(SectionFactory.defaultT1("id", "name", "label", "kind", "enabled", "isDefault"));
      table.withSection(SectionFactory.defaultT2("fromAddress", "fromName", "replyTo", "sendingDomain", "host", "port", "security", "region", "usernameEnvName", "secretEnvName", "webhookSecretEnvName", "snsTopicArn", "configurationSet", "messageStream", "maxPerMinute", "maxPerDay", "trackOpens", "trackClicks", "phiAllowed", "healthStatus", "lastSuccessDate", "lastFailureDate", "lastFailureMessage"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      table.withSection(SectionFactory.customT2("children", new QIcon("list")).withLabel("Messages").withWidgetName(PROVIDER + "_children"));
      table.withSection(SectionFactory.customT2("inbox", new QIcon("list")).withLabel("Webhook Inbox").withWidgetName(INBOX + "_children"));
      return (table);
   }


   /*******************************************************************************
    ** Metadata for email_message.
    *******************************************************************************/
   private static QTableMetaData message(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, MESSAGE, "Message")
         .withField(field("status", QFieldType.STRING).withPossibleValueSourceName("email_message_status").withMaxLength(500))
         .withField(field("toAddresses", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("ccAddresses", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("bccAddresses", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("fromAddress", QFieldType.STRING).withIsSensitive(true).withMaxLength(500))
         .withField(field("replyTo", QFieldType.STRING).withIsSensitive(true).withMaxLength(500))
         .withField(field("subject", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("templateId", QFieldType.INTEGER).withPossibleValueSourceName(TEMPLATE))
         .withField(field("templateVersionId", QFieldType.INTEGER).withPossibleValueSourceName(VERSION).withPossibleValueSourceFilter(com.kingsrook.qbits.email.support.EmailStore.equal("templateId", "${input.templateId}")))
         .withField(field("brandId", QFieldType.INTEGER).withPossibleValueSourceName(BRAND))
         .withField(field("category", QFieldType.STRING).withPossibleValueSourceName("email_category").withMaxLength(500))
         .withField(field("locale", QFieldType.STRING).withDefaultValue("en").withMaxLength(500))
         .withField(field("variablesSnapshot", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("htmlBody", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("textBody", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("phiApproved", QFieldType.BOOLEAN).withDefaultValue(false).withIsEditable(false))
         .withField(field("unsubscribeUrl", QFieldType.TEXT))
         .withField(field("bodySha256", QFieldType.STRING).withMaxLength(500))
         .withField(field("idempotencyKey", QFieldType.STRING).withMaxLength(500))
         .withField(field("providerId", QFieldType.INTEGER).withPossibleValueSourceName(PROVIDER))
         .withField(field("providerMessageId", QFieldType.STRING).withMaxLength(500))
         .withField(field("attemptCount", QFieldType.INTEGER).withDefaultValue(0))
         .withField(field("nextAttemptDate", QFieldType.DATE_TIME))
         .withField(field("leaseExpiresDate", QFieldType.DATE_TIME))
         .withField(field("lastError", QFieldType.STRING).withMaxLength(500))
         .withField(field("finishedDate", QFieldType.DATE_TIME))
         .withField(field("sentDate", QFieldType.DATE_TIME))
         .withField(field("deliveredDate", QFieldType.DATE_TIME))
         .withField(field("bouncedDate", QFieldType.DATE_TIME))
         .withField(field("complainedDate", QFieldType.DATE_TIME))
         .withField(field("deadLetterDate", QFieldType.DATE_TIME))
         .withField(field("linkedTableName", QFieldType.STRING).withMaxLength(500))
         .withField(field("linkedRecordId", QFieldType.STRING).withMaxLength(500)
            .withFieldAdornment(new FieldAdornment(AdornmentType.LINK).withValue(AdornmentType.LinkValues.TO_RECORD_FROM_TABLE_DYNAMIC, true)))
         .withField(field("senderQbit", QFieldType.STRING).withMaxLength(500))
         .withField(field("senderProcess", QFieldType.STRING).withMaxLength(500))
         .withField(field("suppressedAddresses", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("sendAfter", QFieldType.DATE_TIME));
      table.withUniqueKey(new UniqueKey("idempotencyKey"));
      table.withSection(SectionFactory.defaultT1("id", "status", "toAddresses", "ccAddresses", "bccAddresses", "fromAddress"));
      table.withSection(SectionFactory.defaultT2("replyTo", "subject", "templateId", "templateVersionId", "brandId", "category", "locale", "variablesSnapshot", "htmlBody", "textBody", "phiApproved", "unsubscribeUrl", "bodySha256", "idempotencyKey", "providerId", "providerMessageId", "attemptCount", "nextAttemptDate", "leaseExpiresDate", "lastError", "finishedDate", "sentDate", "deliveredDate", "bouncedDate", "complainedDate", "deadLetterDate", "linkedTableName", "linkedRecordId", "senderQbit", "senderProcess", "suppressedAddresses", "sendAfter"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      table.withSection(SectionFactory.customT2("children", new QIcon("list")).withLabel("Attempts").withWidgetName(MESSAGE + "_children"));
      table.withSection(SectionFactory.customT2("events", new QIcon("list")).withLabel("Events").withWidgetName(EVENT + "_children"));
      return (table);
   }


   /*******************************************************************************
    ** Metadata for email_message_attempt.
    *******************************************************************************/
   private static QTableMetaData messageattempt(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, ATTEMPT, "Message Attempt")
         .withField(field("messageId", QFieldType.INTEGER).withPossibleValueSourceName(MESSAGE).withIsRequired(true))
         .withField(field("attemptNumber", QFieldType.INTEGER).withIsRequired(true))
         .withField(field("outcome", QFieldType.STRING).withPossibleValueSourceName("email_attempt_outcome").withMaxLength(500))
         .withField(field("providerId", QFieldType.INTEGER).withPossibleValueSourceName(PROVIDER))
         .withField(field("claimedBy", QFieldType.STRING).withMaxLength(500))
         .withField(field("startDate", QFieldType.DATE_TIME))
         .withField(field("endDate", QFieldType.DATE_TIME))
         .withField(field("leaseExpiresDate", QFieldType.DATE_TIME))
         .withField(field("durationMillis", QFieldType.INTEGER))
         .withField(field("error", QFieldType.STRING).withMaxLength(500));
      table.withUniqueKey(new UniqueKey("messageId", "attemptNumber"));
      table.withSection(SectionFactory.defaultT1("id", "messageId", "attemptNumber", "outcome", "providerId", "claimedBy"));
      table.withSection(SectionFactory.defaultT2("startDate", "endDate", "leaseExpiresDate", "durationMillis", "error"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }


   /*******************************************************************************
    ** Metadata for email_template.
    *******************************************************************************/
   private static QTableMetaData template(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, TEMPLATE, "Template")
         .withField(field("name", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("label", QFieldType.STRING).withMaxLength(500))
         .withField(field("category", QFieldType.STRING).withPossibleValueSourceName("email_category").withMaxLength(500))
         .withField(field("kind", QFieldType.STRING).withPossibleValueSourceName("email_template_kind").withIsRequired(true).withMaxLength(500))
         .withField(field("brandId", QFieldType.INTEGER).withPossibleValueSourceName(BRAND))
         .withField(field("activeVersionId", QFieldType.INTEGER).withPossibleValueSourceName(VERSION).withIsEditable(false).withPossibleValueSourceFilter(com.kingsrook.qbits.email.support.EmailStore.equal("templateId", "${input.id}")))
         .withField(field("approvedVersionId", QFieldType.INTEGER).withIsEditable(false).withPossibleValueSourceName(VERSION).withPossibleValueSourceFilter(com.kingsrook.qbits.email.support.EmailStore.equal("templateId", "${input.id}")))
         .withField(field("versionLock", QFieldType.INTEGER).withIsHidden(true).withIsEditable(false))
         .withField(field("phiApproved", QFieldType.BOOLEAN).withDefaultValue(false).withIsEditable(false))
         .withField(field("phiApprovedBy", QFieldType.STRING).withMaxLength(500).withIsEditable(false))
         .withField(field("phiApprovedDate", QFieldType.DATE_TIME).withIsEditable(false))
         .withField(field("editPermissionName", QFieldType.STRING).withMaxLength(500))
         .withField(field("ownerQbit", QFieldType.STRING).withMaxLength(500))
         .withField(field("description", QFieldType.TEXT));
      table.withRecordLabelFormatAndFields("%s", "name");
      table.withUniqueKey(new UniqueKey("name"));
      table.withSection(SectionFactory.defaultT1("id", "name", "label", "category", "kind", "brandId"));
      table.withSection(SectionFactory.defaultT2("activeVersionId", "approvedVersionId", "phiApproved", "phiApprovedBy", "phiApprovedDate", "editPermissionName", "ownerQbit", "description"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      table.withSection(SectionFactory.customT2("children", new QIcon("list")).withLabel("Versions").withWidgetName(TEMPLATE + "_children"));
      return (table);
   }


   /*******************************************************************************
    ** Metadata for email_template_version.
    *******************************************************************************/
   private static QTableMetaData templateversion(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, VERSION, "Template Version")
         .withField(field("templateId", QFieldType.INTEGER).withPossibleValueSourceName(TEMPLATE).withIsRequired(true))
         .withField(field("versionNumber", QFieldType.INTEGER).withIsRequired(true))
         .withField(field("status", QFieldType.STRING).withPossibleValueSourceName("email_version_status").withDefaultValue("DRAFT").withIsEditable(false).withMaxLength(500))
         .withField(field("locale", QFieldType.STRING).withDefaultValue("en").withMaxLength(500).withIsRequired(true))
         .withField(field("subject", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("preheader", QFieldType.TEXT))
         .withField(field("bodySource", QFieldType.TEXT))
         .withField(field("textSource", QFieldType.TEXT))
         .withField(field("compiledHtml", QFieldType.TEXT).withIsEditable(false))
         .withField(field("variablesSchema", QFieldType.TEXT))
         .withField(field("sampleVariables", QFieldType.TEXT))
         .withField(field("createdBy", QFieldType.STRING).withMaxLength(500))
         .withField(field("activatedDate", QFieldType.DATE_TIME));
      table.withUniqueKey(new UniqueKey("templateId", "versionNumber", "locale"));
      table.withSection(SectionFactory.customT2("preview", new QIcon("preview")).withLabel("Preview").withWidgetName("emailTemplatePreview"));
      table.withSection(SectionFactory.defaultT1("id", "templateId", "versionNumber", "status", "locale", "subject"));
      table.withSection(SectionFactory.defaultT2("preheader", "bodySource", "textSource", "compiledHtml", "variablesSchema", "sampleVariables", "createdBy", "activatedDate"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }


   /*******************************************************************************
    ** Metadata for email_brand.
    *******************************************************************************/
   private static QTableMetaData brand(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, BRAND, "Brand")
         .withField(field("name", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("label", QFieldType.STRING).withMaxLength(500))
         .withField(field("logoUrl", QFieldType.STRING).withMaxLength(500))
         .withField(field("logoDarkUrl", QFieldType.STRING).withMaxLength(500))
         .withField(field("logoAlt", QFieldType.STRING).withMaxLength(500))
         .withField(field("primaryColor", QFieldType.STRING).withMaxLength(500))
         .withField(field("textColor", QFieldType.STRING).withMaxLength(500))
         .withField(field("backgroundColor", QFieldType.STRING).withMaxLength(500))
         .withField(field("darkBackgroundColor", QFieldType.STRING).withMaxLength(500))
         .withField(field("darkPrimaryColor", QFieldType.STRING).withMaxLength(500))
         .withField(field("darkButtonTextColor", QFieldType.STRING).withMaxLength(500))
         .withField(field("darkTextColor", QFieldType.STRING).withMaxLength(500))
         .withField(field("buttonTextColor", QFieldType.STRING).withMaxLength(500))
         .withField(field("footerMarkdown", QFieldType.TEXT))
         .withField(field("postalAddress", QFieldType.TEXT))
         .withField(field("socialLinks", QFieldType.TEXT))
         .withField(field("siteUrl", QFieldType.STRING).withMaxLength(500))
         .withField(field("isDefault", QFieldType.BOOLEAN).withDefaultValue(false))
         .withField(field("appName", QFieldType.STRING).withMaxLength(500));
      table.withRecordLabelFormatAndFields("%s", "name");
      table.withUniqueKey(new UniqueKey("name"));
      table.withSection(SectionFactory.defaultT1("id", "name", "label", "logoUrl", "logoDarkUrl", "logoAlt"));
      table.withSection(SectionFactory.defaultT2("primaryColor", "textColor", "backgroundColor", "darkBackgroundColor", "darkTextColor", "darkPrimaryColor", "darkButtonTextColor", "buttonTextColor", "footerMarkdown", "postalAddress", "socialLinks", "siteUrl", "isDefault", "appName"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }


   /*******************************************************************************
    ** Metadata for email_suppression.
    *******************************************************************************/
   private static QTableMetaData suppression(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, SUPPRESSION, "Suppression")
         .withField(field("address", QFieldType.STRING).withIsRequired(true).withMaxLength(500))
         .withField(field("scope", QFieldType.STRING).withPossibleValueSourceName("email_suppression_scope").withIsRequired(true).withMaxLength(500))
         .withField(field("reason", QFieldType.STRING).withPossibleValueSourceName("email_suppression_reason").withIsRequired(true).withMaxLength(500))
         .withField(field("sourceMessageId", QFieldType.INTEGER))
         .withField(field("sourceEventId", QFieldType.INTEGER).withPossibleValueSourceName(EVENT))
         .withField(field("note", QFieldType.TEXT))
         .withField(field("createdDate", QFieldType.DATE_TIME))
         .withField(field("expiresDate", QFieldType.DATE_TIME));
      table.withUniqueKey(new UniqueKey("address", "scope"));
      table.withSection(SectionFactory.defaultT1("id", "address", "scope", "reason", "sourceMessageId", "sourceEventId"));
      table.withSection(SectionFactory.defaultT2("note", "createdDate", "expiresDate"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }


   /*******************************************************************************
    ** System-written webhook inbox records.
    *******************************************************************************/
   private static QTableMetaData inbox(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, INBOX, "Webhook Inbox")
         .withField(field("providerId", QFieldType.INTEGER).withIsRequired(true).withPossibleValueSourceName(PROVIDER))
         .withField(field("kind", QFieldType.STRING).withMaxLength(500))
         .withField(field("externalEventId", QFieldType.STRING).withMaxLength(500).withIsRequired(true))
         .withField(field("nonce", QFieldType.STRING).withMaxLength(500))
         .withField(field("status", QFieldType.STRING).withMaxLength(500).withIsRequired(true).withPossibleValueSourceName("email_inbox_status"))
         .withField(field("attempts", QFieldType.INTEGER))
         .withField(field("nextAttemptDate", QFieldType.DATE_TIME))
         .withField(field("lastError", QFieldType.STRING).withMaxLength(500))
         .withField(field("payloadJson", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("payloadSha256", QFieldType.STRING).withMaxLength(500))
         .withField(field("receivedDate", QFieldType.DATE_TIME))
         .withField(field("processedDate", QFieldType.DATE_TIME));
      table.withUniqueKey(new UniqueKey("providerId", "externalEventId"));
      table.withUniqueKey(new UniqueKey("providerId", "nonce"));
      table.withSection(SectionFactory.defaultT1("id", "providerId", "kind", "externalEventId", "nonce", "status", "attempts", "nextAttemptDate", "lastError", "payloadJson", "payloadSha256", "receivedDate", "processedDate"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }

   /*******************************************************************************
    ** System-written event records.
    *******************************************************************************/
   private static QTableMetaData event(EmailQBitConfig config)
   {
      QTableMetaData table = base(config, EVENT, "Event")
         .withField(field("messageId", QFieldType.INTEGER).withIsRequired(true).withPossibleValueSourceName(MESSAGE))
         .withField(field("providerId", QFieldType.INTEGER).withPossibleValueSourceName(PROVIDER))
         .withField(field("eventType", QFieldType.STRING).withMaxLength(500).withIsRequired(true).withPossibleValueSourceName("email_event_type"))
         .withField(field("bounceType", QFieldType.STRING).withMaxLength(500).withPossibleValueSourceName("email_bounce_type"))
         .withField(field("recipient", QFieldType.STRING).withMaxLength(500).withIsSensitive(true))
         .withField(field("occurredDate", QFieldType.DATE_TIME))
         .withField(field("detail", QFieldType.STRING).withMaxLength(500))
         .withField(field("url", QFieldType.TEXT).withIsSensitive(true))
         .withField(field("eventKey", QFieldType.STRING).withMaxLength(500).withIsRequired(true).withIsHidden(true));
      table.withUniqueKey(new UniqueKey("eventKey"));
      table.withSection(SectionFactory.defaultT1("id", "messageId", "providerId", "eventType", "bounceType", "recipient", "occurredDate", "detail", "url"));
      table.withSection(SectionFactory.defaultT3("createDate", "modifyDate"));
      return (table);
   }

   /*******************************************************************************
    ** Relations used by child lists.
    *******************************************************************************/
   public static List<QJoinMetaData> joins()
   {
      return (List.of(join(PROVIDER, MESSAGE, "providerId"), join(MESSAGE, ATTEMPT, "messageId"), join(TEMPLATE, VERSION, "templateId"), join(PROVIDER, INBOX, "providerId"), join(MESSAGE, EVENT, "messageId")));
   }

   /*******************************************************************************
    ** Parent to child relation.
    *******************************************************************************/
   private static QJoinMetaData join(String parent, String child, String field)
   {
      return (new QJoinMetaData().withName(parent + "_join_" + child).withType(JoinType.ONE_TO_MANY)
         .withLeftTable(parent).withRightTable(child).withJoinOn(new JoinOn("id", field)));
   }

   /*******************************************************************************
    ** Child lists live on the parent record, never in navigation.
    *******************************************************************************/
   public static List<QWidgetMetaData> childWidgets()
   {
      List<QWidgetMetaData> widgets = new ArrayList<>();
      for(QJoinMetaData join : joins())
      {
         widgets.add(ChildRecordListRenderer.widgetMetaDataBuilder(join).withName(List.of(INBOX, EVENT).contains(join.getRightTable()) ? join.getRightTable() + "_children" : join.getLeftTable() + "_children")
            .withLabel(join.getRightTable().equals(INBOX) ? "Webhook Inbox" : join.getRightTable().equals(EVENT) ? "Events" : join.getRightTable().equals(ATTEMPT) ? "Attempts" : join.getRightTable().equals(VERSION) ? "Versions" : "Messages")
            .withMaxRows(50).getWidgetMetaData().withGridColumns(12).withPermissionRules(permissionRules()));
      }
      return (widgets);
   }
}
